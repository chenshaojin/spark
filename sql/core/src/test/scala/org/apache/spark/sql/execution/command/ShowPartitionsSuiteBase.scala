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

package org.apache.spark.sql.execution.command

import org.apache.spark.sql.{AnalysisException, QueryTest, Row}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{StringType, StructType}

trait ShowPartitionsSuiteBase extends QueryTest with DDLCommandTestUtils {
  override val command = "SHOW PARTITIONS"
  // Gets the schema of `SHOW PARTITIONS`
  private val showSchema: StructType = new StructType().add("partition", StringType, false)
  protected def runShowPartitionsSql(sqlText: String, expected: Seq[Row]): Unit = {
    val df = spark.sql(sqlText)
    assert(df.schema === showSchema)
    checkAnswer(df, expected)
  }

  protected def createDateTable(table: String): Unit = {
    sql(s"""
      |CREATE TABLE $table (price int, qty int, year int, month int)
      |$defaultUsing
      |partitioned by (year, month)""".stripMargin)
    sql(s"INSERT INTO $table PARTITION(year = 2015, month = 1) SELECT 1, 1")
    sql(s"INSERT INTO $table PARTITION(year = 2015, month = 2) SELECT 2, 2")
    sql(s"ALTER TABLE $table ADD PARTITION(year = 2016, month = 2)")
    sql(s"ALTER TABLE $table ADD PARTITION(year = 2016, month = 3)")
  }

  test("show partitions of non-partitioned table") {
    withNamespaceAndTable("ns", "not_partitioned_table") { t =>
      sql(s"CREATE TABLE $t (col1 int) $defaultUsing")
      val errMsg = intercept[AnalysisException] {
        sql(s"SHOW PARTITIONS $t")
      }.getMessage
      assert(errMsg.contains("not allowed on a table that is not partitioned"))
    }
  }

  test("non-partitioning columns") {
    withNamespaceAndTable("ns", "dateTable") { t =>
      createDateTable(t)
      val errMsg = intercept[AnalysisException] {
        sql(s"SHOW PARTITIONS $t PARTITION(abcd=2015, xyz=1)")
      }.getMessage
      assert(errMsg.contains("abcd is not a valid partition column"))
    }
  }

  test("show everything") {
    withNamespaceAndTable("ns", "dateTable") { t =>
      createDateTable(t)
      runShowPartitionsSql(
        s"show partitions $t",
        Row("year=2015/month=1") ::
        Row("year=2015/month=2") ::
        Row("year=2016/month=2") ::
        Row("year=2016/month=3") :: Nil)
    }
  }

  test("filter by partitions") {
    withNamespaceAndTable("ns", "dateTable") { t =>
      createDateTable(t)
      runShowPartitionsSql(
        s"show partitions $t PARTITION(year=2015)",
        Row("year=2015/month=1") ::
        Row("year=2015/month=2") :: Nil)
      runShowPartitionsSql(
        s"show partitions $t PARTITION(year=2015, month=1)",
        Row("year=2015/month=1") :: Nil)
      runShowPartitionsSql(
        s"show partitions $t PARTITION(month=2)",
        Row("year=2015/month=2") ::
        Row("year=2016/month=2") :: Nil)
    }
  }

  test("show everything more than 5 part keys") {
    withNamespaceAndTable("ns", "wideTable") { t =>
      createWideTable(t)
      runShowPartitionsSql(
        s"show partitions $t",
        Row("year=2016/month=3/hour=10/minute=10/sec=10/extra=1") ::
        Row("year=2016/month=4/hour=10/minute=10/sec=10/extra=1") :: Nil)
    }
  }

  test("SPARK-33667: case sensitivity of partition spec") {
    withNamespaceAndTable("ns", "part_table") { t =>
      sql(s"""
        |CREATE TABLE $t (price int, qty int, year int, month int)
        |$defaultUsing
        |PARTITIONED BY (year, month)""".stripMargin)
      sql(s"INSERT INTO $t PARTITION(year = 2015, month = 1) SELECT 1, 1")
      Seq(
        true -> "PARTITION(year = 2015, month = 1)",
        false -> "PARTITION(YEAR = 2015, Month = 1)"
      ).foreach { case (caseSensitive, partitionSpec) =>
        withSQLConf(SQLConf.CASE_SENSITIVE.key -> caseSensitive.toString) {
          runShowPartitionsSql(
            s"SHOW PARTITIONS $t $partitionSpec",
            Row("year=2015/month=1") :: Nil)
        }
      }
    }
  }

  test("SPARK-33777: sorted output") {
    withNamespaceAndTable("ns", "dateTable") { t =>
      sql(s"""
        |CREATE TABLE $t (id int, part string)
        |$defaultUsing
        |PARTITIONED BY (part)""".stripMargin)
      sql(s"ALTER TABLE $t ADD PARTITION(part = 'b')")
      sql(s"ALTER TABLE $t ADD PARTITION(part = 'a')")
      val partitions = sql(s"show partitions $t")
      assert(partitions.first().getString(0) === "part=a")
    }
  }
}
