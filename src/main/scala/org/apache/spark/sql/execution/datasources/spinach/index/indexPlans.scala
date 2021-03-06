/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.spinach.index

import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce.Job

import org.apache.spark.SparkException
import org.apache.spark.internal.Logging
import org.apache.spark.sql.{AnalysisException, Dataset, Row, SparkSession}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, Project}
import org.apache.spark.sql.execution.SQLExecution
import org.apache.spark.sql.execution.command.RunnableCommand
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.execution.datasources.parquet.ParquetFileFormat
import org.apache.spark.sql.execution.datasources.spinach._
import org.apache.spark.sql.execution.datasources.spinach.utils.SpinachUtils
import org.apache.spark.sql.types.{IntegerType, StringType, StructType}


/**
 * Creates an index for table on indexColumns
 */
case class CreateIndex(
    indexName: String,
    relation : LogicalPlan,
    indexColumns: Array[IndexColumn],
    allowExists: Boolean,
    indexType: AnyIndexType) extends RunnableCommand with Logging {
  override def children: Seq[LogicalPlan] = Seq(relation)

  override val output: Seq[Attribute] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val (fileCatalog, s, readerClassName, identifier) = relation match {
      case LogicalRelation(
      HadoopFsRelation(_, fileCatalog, _, s, _, _: SpinachFileFormat, _), _, id) =>
        (fileCatalog, s, SpinachFileFormat.SPINACH_DATA_FILE_CLASSNAME, id)
      case LogicalRelation(
      HadoopFsRelation(_, fileCatalog, _, s, _, _: ParquetFileFormat, _), _, id) =>
        (fileCatalog, s, SpinachFileFormat.PARQUET_DATA_FILE_CLASSNAME, id)
      case other =>
        throw new SpinachException(s"We don't support index building for ${other.simpleString}")
    }

    logInfo(s"Creating index $indexName")
    val partitions = SpinachUtils.getPartitions(fileCatalog)
    // TODO currently we ignore empty partitions, so each partition may have different indexes,
    // this may impact index updating. It may also fail index existence check. Should put index
    // info at table level also.
    val bAndP = partitions.filter(_.files.nonEmpty).map(p => {
      val metaBuilder = new DataSourceMetaBuilder()
      val parent = p.files.head.getPath.getParent
      // TODO get `fs` outside of map() to boost
      val fs = parent.getFileSystem(sparkSession.sparkContext.hadoopConfiguration)
      val existOld = fs.exists(new Path(parent, SpinachFileFormat.SPINACH_META_FILE))
      if (existOld) {
        val m = SpinachUtils.getMeta(sparkSession.sparkContext.hadoopConfiguration, parent)
        assert(m.nonEmpty)
        val oldMeta = m.get
        val existsIndexes = oldMeta.indexMetas
        val existsData = oldMeta.fileMetas
        if (existsIndexes.exists(_.name == indexName)) {
          if (!allowExists) {
            throw new AnalysisException(
              s"""Index $indexName exists on ${identifier.getOrElse(parent)}""")
          } else {
            logWarning(s"Dup index name $indexName")
          }
        }
        if (existsData != null) existsData.foreach(metaBuilder.addFileMeta)
        if (existsIndexes != null) {
          existsIndexes.filter(_.name != indexName).foreach(metaBuilder.addIndexMeta)
        }
        metaBuilder.withNewSchema(oldMeta.schema)
      } else {
        metaBuilder.withNewSchema(s)
      }

      indexType match {
        case BTreeIndexType =>
          val entries = indexColumns.map(c => {
            val dir = if (c.isAscending) Ascending else Descending
            BTreeIndexEntry(s.map(_.name).toIndexedSeq.indexOf(c.columnName), dir)
          })
          metaBuilder.addIndexMeta(new IndexMeta(indexName, BTreeIndex(entries)))
        case BitMapIndexType =>
          val entries = indexColumns.map(col => s.map(_.name).toIndexedSeq.indexOf(col.columnName))
          metaBuilder.addIndexMeta(new IndexMeta(indexName, BitMapIndex(entries)))
        case _ =>
          sys.error(s"Not supported index type $indexType")
      }

      // we cannot build meta for those without spinach meta data
      metaBuilder.withNewDataReaderClassName(readerClassName)
      // when p.files is nonEmpty but no spinach meta, it means the relation is in parquet
      // (else it is Spinach empty partition, we won't create meta for them).
      // For Parquet, we only use Spinach meta to track schema and reader class, as well as
      // `IndexMeta`s that must be empty at the moment, so `FileMeta`s are ok to leave empty.
      // p.files.foreach(f => builder.addFileMeta(FileMeta("", 0, f.getPath.toString)))
      (metaBuilder, parent, existOld)
    })
    val job = Job.getInstance(sparkSession.sparkContext.hadoopConfiguration)
    val indexFileFormat = new SpinachIndexFileFormat
    val ids =
      indexColumns.map(c => s.map(_.name).toIndexedSeq.indexOf(c.columnName))
    val keySchema = StructType(ids.map(s.toIndexedSeq(_)))
    val queryExecution =
      Dataset.ofRows(sparkSession, Project(ids.map(relation.output), relation)).queryExecution
    val retVal = SQLExecution.withNewExecutionId(sparkSession, queryExecution) {
      val indexRelation =
        WriteIndexRelation(
          sparkSession,
          keySchema,
          s,
          readerClassName,
          indexFileFormat.prepareWrite(sparkSession, _, null, keySchema))

      val writerContainer = {
        // TODO Partition and bucket TBD
        IndexWriterFactory.getIndexWriter(indexRelation,
          job,
          indexColumns,
          keySchema,
          indexName,
          isAppend = false,
          indexType)
      }

      // This call shouldn't be put into the `try` block below because it only initializes and
      // prepares the job, any exception thrown from here shouldn't cause abortJob() to be called.
      writerContainer.driverSideSetup()

      try {
        val results = sparkSession.sparkContext.runJob(
          queryExecution.toRdd, writerContainer.writeIndexFromRows _)
        writerContainer.commitJob(results.flatten)
        results.flatten
      } catch { case cause: Throwable =>
        logError("Aborting job.", cause)
        writerContainer.abortJob()
        throw new SparkException("Job aborted.", cause)
      }
    }.toSeq
//    val ret = SpinachIndexBuild(sparkSession, indexName,
//      indexColumns, s, bAndP.map(_._2), readerClassName, indexType).execute()
    val retMap = retVal.groupBy(_.parent)
    bAndP.foreach(bp =>
      retMap.getOrElse(bp._2.toString, Nil).foreach(r =>
        if (!bp._3) bp._1.addFileMeta(
          FileMeta(r.fingerprint, r.rowCount, r.dataFile))
      ))
    // write updated metas down
    bAndP.foreach(bp => DataSourceMeta.write(
      new Path(bp._2.toString, SpinachFileFormat.SPINACH_META_FILE),
      sparkSession.sparkContext.hadoopConfiguration,
      bp._1.build(),
      deleteIfExits = true))
    Seq.empty
  }
}

/**
 * Drops an index
 */
case class DropIndex(
    indexName: String,
    relation: LogicalPlan,
    allowNotExists: Boolean) extends RunnableCommand {

  override def children: Seq[LogicalPlan] = Seq(relation)

  override val output: Seq[Attribute] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    relation match {
      case LogicalRelation(HadoopFsRelation(_, fileCatalog, _, _, _, format, _), _, identifier)
          if format.isInstanceOf[SpinachFileFormat] || format.isInstanceOf[ParquetFileFormat] =>
        logInfo(s"Dropping index $indexName")
        val partitions = SpinachUtils.getPartitions(fileCatalog)
        partitions.filter(_.files.nonEmpty).foreach(p => {
          val parent = p.files.head.getPath.getParent
          // TODO get `fs` outside of foreach() to boost
          val fs = parent.getFileSystem(sparkSession.sparkContext.hadoopConfiguration)
          if (fs.exists(new Path(parent, SpinachFileFormat.SPINACH_META_FILE))) {
            val metaBuilder = new DataSourceMetaBuilder()
            val m = SpinachUtils.getMeta(sparkSession.sparkContext.hadoopConfiguration, parent)
            assert(m.nonEmpty)
            val oldMeta = m.get
            val existsIndexes = oldMeta.indexMetas
            val existsData = oldMeta.fileMetas
            if (!existsIndexes.exists(_.name == indexName)) {
              if (!allowNotExists) {
                throw new AnalysisException(
                  s"""Index $indexName does not exist on ${identifier.getOrElse(parent)}""")
              } else {
                logWarning(s"drop non-exists index $indexName")
              }
            }
            if (existsData != null) existsData.foreach(metaBuilder.addFileMeta)
            if (existsIndexes != null) {
              existsIndexes.filter(_.name != indexName).foreach(metaBuilder.addIndexMeta)
            }
            metaBuilder.withNewDataReaderClassName(oldMeta.dataReaderClassName)
            DataSourceMeta.write(
              new Path(parent.toString, SpinachFileFormat.SPINACH_META_FILE),
              sparkSession.sparkContext.hadoopConfiguration,
              metaBuilder.withNewSchema(oldMeta.schema).build(),
              deleteIfExits = true)
            val allFile = fs.listFiles(parent, false)
            val filePaths = new Iterator[Path] {
              override def hasNext: Boolean = allFile.hasNext
              override def next(): Path = allFile.next().getPath
            }.toSeq
            filePaths.filter(_.toString.endsWith(
              "." + indexName + SpinachFileFormat.SPINACH_INDEX_EXTENSION)).foreach(
              fs.delete(_, true))
          }
        })
      case other => sys.error(s"We don't support index dropping for ${other.simpleString}")
    }
    Seq.empty
  }
}

/**
 * Refreshes an index for table
 */
case class RefreshIndex(
    relation: LogicalPlan) extends RunnableCommand with Logging {
  override def children: Seq[LogicalPlan] = Seq(relation)

  override val output: Seq[Attribute] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val (fileCatalog, s, readerClassName) = relation match {
      case LogicalRelation(
          HadoopFsRelation(_, fileCatalog, _, s, _, _: SpinachFileFormat, _), _, _) =>
        (fileCatalog, s, SpinachFileFormat.SPINACH_DATA_FILE_CLASSNAME)
      case LogicalRelation(
          HadoopFsRelation(_, fileCatalog, _, s, _, _: ParquetFileFormat, _), _, _) =>
        (fileCatalog, s, SpinachFileFormat.PARQUET_DATA_FILE_CLASSNAME)
      case other =>
        throw new SpinachException(s"We don't support index refreshing for ${other.simpleString}")
    }

    val partitions = SpinachUtils.getPartitions(fileCatalog).filter(_.files.nonEmpty)
    // TODO currently we ignore empty partitions, so each partition may have different indexes,
    // this may impact index updating. It may also fail index existence check. Should put index
    // info at table level also.
    // aggregate all existing indices
    val indices = partitions.flatMap(p => {
      val parent = p.files.head.getPath.getParent
      // TODO get `fs` outside of map() to boost
      val fs = parent.getFileSystem(sparkSession.sparkContext.hadoopConfiguration)
      val existOld = fs.exists(new Path(parent, SpinachFileFormat.SPINACH_META_FILE))
      if (existOld) {
        val m = SpinachUtils.getMeta(sparkSession.sparkContext.hadoopConfiguration, parent)
        assert(m.nonEmpty)
        val oldMeta = m.get
        oldMeta.indexMetas
      } else {
        Nil
      }
    }).groupBy(_.name).map(_._2.head)

    val bAndP = partitions.map(p => {
      val metaBuilder = new DataSourceMetaBuilder()
      val parent = p.files.head.getPath.getParent
      // TODO get `fs` outside of map() to boost
      val fs = parent.getFileSystem(sparkSession.sparkContext.hadoopConfiguration)
      val existOld = fs.exists(new Path(parent, SpinachFileFormat.SPINACH_META_FILE))
      if (existOld) {
        val m = SpinachUtils.getMeta(sparkSession.sparkContext.hadoopConfiguration, parent)
        assert(m.nonEmpty)
        val oldMeta = m.get
        // add filemeta list already exist
        oldMeta.fileMetas.foreach(metaBuilder.addFileMeta)
        // TODO for now we only support data file adding before updating index
        metaBuilder.withNewSchema(oldMeta.schema)
      } else {
        metaBuilder.withNewSchema(s)
      }
      indices.foreach(metaBuilder.addIndexMeta)
      // we cannot build meta for those without spinach meta data
      metaBuilder.withNewDataReaderClassName(readerClassName)
      // when p.files is nonEmpty but no spinach meta, it means the relation is in parquet(else
      // it is Spinach empty partition, we won't create meta for them).
      // For Parquet, we only use Spinach meta to track schema and reader class, as well as
      // `IndexMeta`s that must be empty at the moment, so `FileMeta`s are ok to leave empty.
      // p.files.foreach(f => builder.addFileMeta(FileMeta("", 0, f.getPath.toString)))
      (metaBuilder, parent)
    })

    val buildrst = indices.map(i => {
      var indexType : AnyIndexType = BTreeIndexType

      val indexColumns = i.indexType match {
        case BTreeIndex(entries) =>
          entries.map(e => IndexColumn(s(e.ordinal).name, e.dir == Ascending))
        case BitMapIndex(entries) =>
          indexType = BitMapIndexType
          entries.map(e => IndexColumn(s(e).name))
        case it => sys.error(s"Not implemented index type $it")
      }
      val job = Job.getInstance(sparkSession.sparkContext.hadoopConfiguration)
      val queryExecution = Dataset.ofRows(sparkSession, relation).queryExecution
      val indexFileFormat = new SpinachIndexFileFormat
      val ids =
        indexColumns.map(c => s.map(_.name).toIndexedSeq.indexOf(c.columnName))
      val keySchema = StructType(ids.map(s.toIndexedSeq(_)))
      SQLExecution.withNewExecutionId(sparkSession, queryExecution) {
        val indexRelation =
          WriteIndexRelation(
            sparkSession,
            keySchema,
            s,
            readerClassName,
            indexFileFormat.prepareWrite(sparkSession, _, null, keySchema))

        val writerContainer = {
          // TODO Partition and bucket TBD
          IndexWriterFactory.getIndexWriter(indexRelation,
            job,
            indexColumns.toArray,
            keySchema,
            i.name,
            isAppend = true,
            indexType)
        }

        // This call shouldn't be put into the `try` block below because it only initializes and
        // prepares the job, any exception thrown from here shouldn't cause abortJob() to be called.
        writerContainer.driverSideSetup()

        try {
          val results = sparkSession.sparkContext.runJob(
            queryExecution.toRdd, writerContainer.writeIndexFromRows _)
          writerContainer.commitJob(results.flatten)
          results.flatten
        } catch { case cause: Throwable =>
          logError("Aborting job.", cause)
          writerContainer.abortJob()
          throw new SparkException("Job aborted.", cause)
        }
      }.toSeq
    })
    if (!buildrst.isEmpty) {
      val ret = buildrst.head
      val retMap = ret.groupBy(_.parent)

      // there some cases spn meta files have already been updated
      // e.g. when inserting data in spn files the meta has already updated
      // so, we should ignore these cases
      // And files modifications for parquet should refresh spn meta in this way
      val filteredBAndP = bAndP.filter(x => retMap.contains(x._2.toString))
      filteredBAndP.foreach(bp =>
        retMap.getOrElse(bp._2.toString, Nil).foreach(r => {
          if (!bp._1.containsFileMeta(r.dataFile)) {
            bp._1.addFileMeta(FileMeta(r.fingerprint, r.rowCount, r.dataFile))
          }
        }
      ))

      // write updated metas down
      filteredBAndP.foreach(bp => DataSourceMeta.write(
        new Path(bp._2.toString, SpinachFileFormat.SPINACH_META_FILE),
        sparkSession.sparkContext.hadoopConfiguration,
        bp._1.build(),
        deleteIfExits = true))

      fileCatalog.refresh()
    }

    Seq.empty
  }
}

/**
 * List indices for table
 */
case class SpinachShowIndex(relation: LogicalPlan) extends RunnableCommand with Logging {
  override def children: Seq[LogicalPlan] = Seq(relation)

  override val output: Seq[Attribute] = {
    AttributeReference("table", StringType, nullable = true)() ::
      AttributeReference("key_name", StringType, nullable = false)() ::
      AttributeReference("seq_in_index", IntegerType, nullable = false)() ::
      AttributeReference("column_name", StringType, nullable = false)() ::
      AttributeReference("collation", StringType, nullable = true)() ::
      AttributeReference("index_type", StringType, nullable = false)() :: Nil
  }

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val (fileCatalog, s, readerClassName, identifier) = relation match {
      case LogicalRelation(
      HadoopFsRelation(_, fileCatalog, _, s, _, _: SpinachFileFormat, _), _, id) =>
        (fileCatalog, s, SpinachFileFormat.SPINACH_DATA_FILE_CLASSNAME, id)
      case LogicalRelation(
      HadoopFsRelation(_, fileCatalog, _, s, _, _: ParquetFileFormat, _), _, id) =>
        (fileCatalog, s, SpinachFileFormat.PARQUET_DATA_FILE_CLASSNAME, id)
      case other =>
        throw new SpinachException(s"We don't support index refreshing for ${other.simpleString}")
    }

    val partitions = SpinachUtils.getPartitions(fileCatalog).filter(_.files.nonEmpty)
    // TODO currently we ignore empty partitions, so each partition may have different indexes,
    // this may impact index updating. It may also fail index existence check. Should put index
    // info at table level also.
    // aggregate all existing indices
    val indices = partitions.flatMap(p => {
      val parent = p.files.head.getPath.getParent
      // TODO get `fs` outside of map() to boost
      val fs = parent.getFileSystem(sparkSession.sparkContext.hadoopConfiguration)
      val existOld = fs.exists(new Path(parent, SpinachFileFormat.SPINACH_META_FILE))
      if (existOld) {
        val m = SpinachUtils.getMeta(sparkSession.sparkContext.hadoopConfiguration, parent)
        assert(m.nonEmpty)
        val oldMeta = m.get
        oldMeta.indexMetas
      } else {
        Nil
      }
    }).groupBy(_.name).map(_._2.head)
    indices.toSeq.flatMap(i => i.indexType match {
      case BTreeIndex(entries) =>
        entries.zipWithIndex.map(ei => {
          val dir = if (ei._1.dir == Ascending) "A" else "D"
          Row(identifier.get.table, i.name, ei._2, s(ei._1.ordinal).name, dir, "BTREE")})
      case BitMapIndex(entries) =>
        entries.zipWithIndex.map(ei =>
          Row(identifier.get.table, i.name, ei._2, s(ei._1).name, "A", "BITMAP"))
      case t => sys.error(s"not support index type $t for index ${i.name}")
    })
  }
}
