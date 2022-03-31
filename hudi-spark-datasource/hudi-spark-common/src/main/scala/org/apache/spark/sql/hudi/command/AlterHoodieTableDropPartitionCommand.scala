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

package org.apache.spark.sql.hudi.command

import org.apache.hudi.DataSourceWriteOptions._
import org.apache.hudi.client.common.HoodieSparkEngineContext
import org.apache.hudi.common.fs.FSUtils
import org.apache.hudi.config.HoodieWriteConfig.TBL_NAME
import org.apache.hudi.hive.ddl.HiveSyncMode
import org.apache.hudi.hive.{HiveSyncConfig, MultiPartKeysValueExtractor}
import org.apache.hudi.sync.common.HoodieSyncConfig
import org.apache.hudi.{DataSourceWriteOptions, HoodieSparkSqlWriter}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.catalog.CatalogTypes.TablePartitionSpec
import org.apache.spark.sql.catalyst.catalog.HoodieCatalogTable
import org.apache.spark.sql.execution.command.DDLUtils
import org.apache.spark.sql.hudi.HoodieSqlCommonUtils._
import org.apache.spark.sql.{AnalysisException, Row, SaveMode, SparkSession}

case class AlterHoodieTableDropPartitionCommand(
    tableIdentifier: TableIdentifier,
    specs: Seq[TablePartitionSpec],
    ifExists : Boolean,
    purge : Boolean,
    retainData : Boolean)
  extends HoodieLeafRunnableCommand {

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val fullTableName = s"${tableIdentifier.database}.${tableIdentifier.table}"
    logInfo(s"start execute alter table drop partition command for $fullTableName")

    val hoodieCatalogTable = HoodieCatalogTable(sparkSession, tableIdentifier)

    if (!hoodieCatalogTable.isPartitionedTable) {
      throw new AnalysisException(s"$fullTableName is a non-partitioned table that is not allowed to drop partition")
    }

    DDLUtils.verifyAlterTableType(
      sparkSession.sessionState.catalog, hoodieCatalogTable.table, isView = false)

    val normalizedSpecs: Seq[Map[String, String]] = specs.map { spec =>
      normalizePartitionSpec(
        spec,
        hoodieCatalogTable.partitionFields,
        hoodieCatalogTable.tableName,
        sparkSession.sessionState.conf.resolver)
    }

    val partitionsToDrop = getPartitionPathToDrop(hoodieCatalogTable, normalizedSpecs)
    val parameters = buildHoodieConfig(sparkSession, hoodieCatalogTable, partitionsToDrop)
    HoodieSparkSqlWriter.write(
      sparkSession.sqlContext,
      SaveMode.Append,
      parameters,
      sparkSession.emptyDataFrame)


    // Recursively delete partition directories
    if (purge) {
      val engineContext = new HoodieSparkEngineContext(sparkSession.sparkContext)
      val basePath = hoodieCatalogTable.tableLocation
      val fullPartitionPath = FSUtils.getPartitionPath(basePath, partitionsToDrop)
      logInfo("Clean partition up " + fullPartitionPath)
      val fs = FSUtils.getFs(basePath, sparkSession.sparkContext.hadoopConfiguration)
      FSUtils.deleteDir(engineContext, fs, fullPartitionPath, sparkSession.sparkContext.defaultParallelism)
    }

    sparkSession.catalog.refreshTable(tableIdentifier.unquotedString)
    logInfo(s"Finish execute alter table drop partition command for $fullTableName")
    Seq.empty[Row]
  }

  private def buildHoodieConfig(
      sparkSession: SparkSession,
      hoodieCatalogTable: HoodieCatalogTable,
      partitionsToDrop: String): Map[String, String] = {
    val partitionFields = hoodieCatalogTable.partitionFields.mkString(",")
    val enableHive = isEnableHive(sparkSession)
    withSparkConf(sparkSession, Map.empty) {
      Map(
        "path" -> hoodieCatalogTable.tableLocation,
        TBL_NAME.key -> hoodieCatalogTable.tableName,
        TABLE_TYPE.key -> hoodieCatalogTable.tableTypeName,
        OPERATION.key -> DataSourceWriteOptions.DELETE_PARTITION_OPERATION_OPT_VAL,
        PARTITIONS_TO_DELETE.key -> partitionsToDrop,
        RECORDKEY_FIELD.key -> hoodieCatalogTable.primaryKeys.mkString(","),
        PRECOMBINE_FIELD.key -> hoodieCatalogTable.preCombineKey.getOrElse(""),
        PARTITIONPATH_FIELD.key -> partitionFields,
        HoodieSyncConfig.META_SYNC_ENABLED.key -> enableHive.toString,
        HiveSyncConfig.HIVE_SYNC_ENABLED.key -> enableHive.toString,
        HiveSyncConfig.HIVE_SYNC_MODE.key -> HiveSyncMode.HMS.name(),
        HiveSyncConfig.HIVE_USE_JDBC.key -> "false",
        HoodieSyncConfig.META_SYNC_DATABASE_NAME.key -> hoodieCatalogTable.table.identifier.database.getOrElse("default"),
        HoodieSyncConfig.META_SYNC_TABLE_NAME.key -> hoodieCatalogTable.table.identifier.table,
        HiveSyncConfig.HIVE_SUPPORT_TIMESTAMP_TYPE.key -> "true",
        HoodieSyncConfig.META_SYNC_PARTITION_FIELDS.key -> partitionFields,
        HoodieSyncConfig.META_SYNC_PARTITION_EXTRACTOR_CLASS.key -> classOf[MultiPartKeysValueExtractor].getCanonicalName
      )
    }
  }
}