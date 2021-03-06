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

package org.apache.spark.sql

import net.razorvine.pickle.Pickler

import org.apache.spark.{Dependency, OneToOneDependency, Partition, TaskContext}
import org.apache.spark.annotation.{AlphaComponent, Experimental}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.analysis._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.plans.{Inner, JoinType}
import org.apache.spark.sql.catalyst.types.BooleanType
import org.apache.spark.api.java.JavaRDD
import java.util.{Map => JMap}

/**
 * :: AlphaComponent ::
 * An RDD of [[Row]] objects that has an associated schema. In addition to standard RDD functions,
 * SchemaRDDs can be used in relational queries, as shown in the examples below.
 *
 * Importing a SQLContext brings an implicit into scope that automatically converts a standard RDD
 * whose elements are scala case classes into a SchemaRDD.  This conversion can also be done
 * explicitly using the `createSchemaRDD` function on a [[SQLContext]].
 *
 * A `SchemaRDD` can also be created by loading data in from external sources, for example,
 * by using the `parquetFile` method on [[SQLContext]].
 *
 * == SQL Queries ==
 * A SchemaRDD can be registered as a table in the [[SQLContext]] that was used to create it.  Once
 * an RDD has been registered as a table, it can be used in the FROM clause of SQL statements.
 *
 * {{{
 *  // One method for defining the schema of an RDD is to make a case class with the desired column
 *  // names and types.
 *  case class Record(key: Int, value: String)
 *
 *  val sc: SparkContext // An existing spark context.
 *  val sqlContext = new SQLContext(sc)
 *
 *  // Importing the SQL context gives access to all the SQL functions and implicit conversions.
 *  import sqlContext._
 *
 *  val rdd = sc.parallelize((1 to 100).map(i => Record(i, s"val_\$i")))
 *  // Any RDD containing case classes can be registered as a table.  The schema of the table is
 *  // automatically inferred using scala reflection.
 *  rdd.registerAsTable("records")
 *
 *  val results: SchemaRDD = sql("SELECT * FROM records")
 * }}}
 *
 * == Language Integrated Queries ==
 *
 * {{{
 *
 *  case class Record(key: Int, value: String)
 *
 *  val sc: SparkContext // An existing spark context.
 *  val sqlContext = new SQLContext(sc)
 *
 *  // Importing the SQL context gives access to all the SQL functions and implicit conversions.
 *  import sqlContext._
 *
 *  val rdd = sc.parallelize((1 to 100).map(i => Record(i, "val_" + i)))
 *
 *  // Example of language integrated queries.
 *  rdd.where('key === 1).orderBy('value.asc).select('key).collect()
 * }}}
 *
 *  @groupname Query Language Integrated Queries
 *  @groupdesc Query Functions that create new queries from SchemaRDDs.  The
 *             result of all query functions is also a SchemaRDD, allowing multiple operations to be
 *             chained using a builder pattern.
 *  @groupprio Query -2
 *  @groupname schema SchemaRDD Functions
 *  @groupprio schema -1
 *  @groupname Ungrouped Base RDD Functions
 */
@AlphaComponent
class SchemaRDD(
    @transient val sqlContext: SQLContext,
    @transient protected[spark] val logicalPlan: LogicalPlan)
  extends RDD[Row](sqlContext.sparkContext, Nil) with SchemaRDDLike {

  def baseSchemaRDD = this

  // =========================================================================================
  // RDD functions: Copy the internal row representation so we present immutable data to users.
  // =========================================================================================

  override def compute(split: Partition, context: TaskContext): Iterator[Row] =
    firstParent[Row].compute(split, context).map(_.copy())

  override def getPartitions: Array[Partition] = firstParent[Row].partitions

  override protected def getDependencies: Seq[Dependency[_]] =
    List(new OneToOneDependency(queryExecution.toRdd))


  // =======================================================================
  // Query DSL
  // =======================================================================

  /**
   * Changes the output of this relation to the given expressions, similar to the `SELECT` clause
   * in SQL.
   *
   * {{{
   *   schemaRDD.select('a, 'b + 'c, 'd as 'aliasedName)
   * }}}
   *
   * @param exprs a set of logical expression that will be evaluated for each input row.
   *
   * @group Query
   */
  def select(exprs: NamedExpression*): SchemaRDD =
    new SchemaRDD(sqlContext, Project(exprs, logicalPlan))

  /**
   * Filters the ouput, only returning those rows where `condition` evaluates to true.
   *
   * {{{
   *   schemaRDD.where('a === 'b)
   *   schemaRDD.where('a === 1)
   *   schemaRDD.where('a + 'b > 10)
   * }}}
   *
   * @group Query
   */
  def where(condition: Expression): SchemaRDD =
    new SchemaRDD(sqlContext, Filter(condition, logicalPlan))

  /**
   * Performs a relational join on two SchemaRDDs
   *
   * @param otherPlan the [[SchemaRDD]] that should be joined with this one.
   * @param joinType One of `Inner`, `LeftOuter`, `RightOuter`, or `FullOuter`. Defaults to `Inner.`
   * @param on       An optional condition for the join operation.  This is equivilent to the `ON`
   *                 clause in standard SQL.  In the case of `Inner` joins, specifying a
   *                 `condition` is equivilent to adding `where` clauses after the `join`.
   *
   * @group Query
   */
  def join(
      otherPlan: SchemaRDD,
      joinType: JoinType = Inner,
      on: Option[Expression] = None): SchemaRDD =
    new SchemaRDD(sqlContext, Join(logicalPlan, otherPlan.logicalPlan, joinType, on))

  /**
   * Sorts the results by the given expressions.
   * {{{
   *   schemaRDD.orderBy('a)
   *   schemaRDD.orderBy('a, 'b)
   *   schemaRDD.orderBy('a.asc, 'b.desc)
   * }}}
   *
   * @group Query
   */
  def orderBy(sortExprs: SortOrder*): SchemaRDD =
    new SchemaRDD(sqlContext, Sort(sortExprs, logicalPlan))

  /**
   * Performs a grouping followed by an aggregation.
   *
   * {{{
   *   schemaRDD.groupBy('year)(Sum('sales) as 'totalSales)
   * }}}
   *
   * @group Query
   */
  def groupBy(groupingExprs: Expression*)(aggregateExprs: Expression*): SchemaRDD = {
    val aliasedExprs = aggregateExprs.map {
      case ne: NamedExpression => ne
      case e => Alias(e, e.toString)()
    }
    new SchemaRDD(sqlContext, Aggregate(groupingExprs, aliasedExprs, logicalPlan))
  }

  /**
   * Applies a qualifier to the attributes of this relation.  Can be used to disambiguate attributes
   * with the same name, for example, when peforming self-joins.
   *
   * {{{
   *   val x = schemaRDD.where('a === 1).as('x)
   *   val y = schemaRDD.where('a === 2).as('y)
   *   x.join(y).where("x.a".attr === "y.a".attr),
   * }}}
   *
   * @group Query
   */
  def as(alias: Symbol) =
    new SchemaRDD(sqlContext, Subquery(alias.name, logicalPlan))

  /**
   * Combines the tuples of two RDDs with the same schema, keeping duplicates.
   *
   * @group Query
   */
  def unionAll(otherPlan: SchemaRDD) =
    new SchemaRDD(sqlContext, Union(logicalPlan, otherPlan.logicalPlan))

  /**
   * Filters tuples using a function over the value of the specified column.
   *
   * {{{
   *   schemaRDD.sfilter('a)((a: Int) => ...)
   * }}}
   *
   * @group Query
   */
  def where[T1](arg1: Symbol)(udf: (T1) => Boolean) =
    new SchemaRDD(
      sqlContext,
      Filter(ScalaUdf(udf, BooleanType, Seq(UnresolvedAttribute(arg1.name))), logicalPlan))

  /**
   * :: Experimental ::
   * Filters tuples using a function over a `Dynamic` version of a given Row.  DynamicRows use
   * scala's Dynamic trait to emulate an ORM of in a dynamically typed language.  Since the type of
   * the column is not known at compile time, all attributes are converted to strings before
   * being passed to the function.
   *
   * {{{
   *   schemaRDD.where(r => r.firstName == "Bob" && r.lastName == "Smith")
   * }}}
   *
   * @group Query
   */
  @Experimental
  def where(dynamicUdf: (DynamicRow) => Boolean) =
    new SchemaRDD(
      sqlContext,
      Filter(ScalaUdf(dynamicUdf, BooleanType, Seq(WrapDynamic(logicalPlan.output))), logicalPlan))

  /**
   * :: Experimental ::
   * Returns a sampled version of the underlying dataset.
   *
   * @group Query
   */
  @Experimental
  def sample(
      fraction: Double,
      withReplacement: Boolean = true,
      seed: Int = (math.random * 1000).toInt) =
    new SchemaRDD(sqlContext, Sample(fraction, withReplacement, seed, logicalPlan))

  /**
   * :: Experimental ::
   * Applies the given Generator, or table generating function, to this relation.
   *
   * @param generator A table generating function.  The API for such functions is likely to change
   *                  in future releases
   * @param join when set to true, each output row of the generator is joined with the input row
   *             that produced it.
   * @param outer when set to true, at least one row will be produced for each input row, similar to
   *              an `OUTER JOIN` in SQL.  When no output rows are produced by the generator for a
   *              given row, a single row will be output, with `NULL` values for each of the
   *              generated columns.
   * @param alias an optional alias that can be used as qualifier for the attributes that are
   *              produced by this generate operation.
   *
   * @group Query
   */
  @Experimental
  def generate(
      generator: Generator,
      join: Boolean = false,
      outer: Boolean = false,
      alias: Option[String] = None) =
    new SchemaRDD(sqlContext, Generate(generator, join, outer, None, logicalPlan))

  /**
   * Returns this RDD as a SchemaRDD.  Intended primarily to force the invocation of the implicit
   * conversion from a standard RDD to a SchemaRDD.
   *
   * @group schema
   */
  def toSchemaRDD = this

  private[sql] def javaToPython: JavaRDD[Array[Byte]] = {
    val fieldNames: Seq[String] = this.queryExecution.analyzed.output.map(_.name)
    this.mapPartitions { iter =>
      val pickle = new Pickler
      iter.map { row =>
        val map: JMap[String, Any] = new java.util.HashMap
        // TODO: We place the map in an ArrayList so that the object is pickled to a List[Dict].
        // Ideally we should be able to pickle an object directly into a Python collection so we
        // don't have to create an ArrayList every time.
        val arr: java.util.ArrayList[Any] = new java.util.ArrayList
        row.zip(fieldNames).foreach { case (obj, name) =>
          map.put(name, obj)
        }
        arr.add(map)
        pickle.dumps(arr)
      }
    }
  }
}
