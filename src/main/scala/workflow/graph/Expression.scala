package workflow.graph

import org.apache.spark.rdd.RDD

/**
 * Output is a trait extended by everything that may be output by an [[Operator]].
 * It is intended to add some extra type checking to the internal operator execution.
 */
private[graph] sealed trait Expression

/**
 * This is an output that wraps around an [[RDD]]. It wraps the RDD as call-by-name, so the RDD
 * need not have been computed yet by the time this output is created.
 *
 * The first time the contained value is accessed using `get`, it will be computed. Every time after
 * that it will already be stored, and will not be computed.
 */
private[graph] class DatasetExpression(compute: => RDD[_]) extends Expression {
  lazy val get: RDD[_] = compute
}

/**
 * This is an output that wraps around a single untyped [[Any]] datum. It wraps the datum as call-by-name,
 * so it need not have been computed by the time this output is created.
 *
 * The first time the contained value is accessed using `get`, it will be computed. Every time after
 * that it will already be stored, and will not be computed.
 */
private[graph] class DatumExpression(compute: => Any) extends Expression {
  lazy val get: Any = compute
}

/**
 * This is an output that wraps around a [[TransformerOperator]]. It wraps the transformer as call-by-name,
 * so it need not have been computed by the time this output is created.
 *
 * The first time the contained value is accessed using `get`, it will be computed. Every time after
 * that it will already be stored, and will not be computed.
 */
private[graph] class TransformerExpression(compute: => TransformerOperator) extends Expression {
  lazy val get: TransformerOperator = compute
}
