package workflow

import org.apache.spark.rdd.RDD
import pipelines.Logging

import scala.collection.mutable

/**
 * An implementation of [[Pipeline]] that explicitly specifies the Pipline DAG as vals,
 * With and apply method that works via traversing the DAG graph and executing it.
 */
private[workflow] class ConcretePipeline[A, B](
  private[workflow] override val nodes: Seq[Node],
  private[workflow] override val dataDeps: Seq[Seq[Int]],
  private[workflow] override val fitDeps: Seq[Option[Int]],
  private[workflow] override val sink: Int) extends Pipeline[A, B] with Logging {

  validate()

  private val fitCache: Array[Option[TransformerNode]] = nodes.map(_ => None).toArray
  private val dataCache: mutable.Map[(Int, RDD[_]), RDD[_]] = new mutable.HashMap()
  private val optimizedPipelineCache: mutable.Map[Optimizer, Pipeline[A, B]] = new mutable.HashMap()

  /** validates (returns an exception if false) that
   * - nodes.size = dataDeps.size == fitDeps.size

   * - there is a valid sink
   * - data nodes must have no deps
   * - estimators may not have fit deps, must have data deps
   * - transformers must have data deps, allowed to have fit deps

   * - data deps may not point at estimators
   * - fit deps may only point to estimators
   */
  private[workflow] def validate(): Unit = {
    require(nodes.size == dataDeps.size && nodes.size == fitDeps.size,
      "nodes.size must equal dataDeps.size and fitDeps.size")
    require(sink == Pipeline.SOURCE || (sink >= 0 && sink < nodes.size),
      "Sink must point at a valid node")

    val nodeTuples = nodes.zip(dataDeps).zip(fitDeps).map(x => (x._1._1, x._1._2, x._2))
    require(nodeTuples.forall {
      x => if (x._1.isInstanceOf[SourceNode] && (x._2.nonEmpty || x._3.nonEmpty)) false else true
    }, "DataNodes may not have dependencies")
    require(nodeTuples.forall {
      x => if (x._1.isInstanceOf[EstimatorNode] && x._3.nonEmpty) false else true
    }, "Estimators may not have fit dependencies")
    require(nodeTuples.forall {
      x => if (x._1.isInstanceOf[EstimatorNode] && x._2.isEmpty) false else true
    }, "Estimators must have data dependencies")
    require(nodeTuples.forall {
      x => if (x._1.isInstanceOf[TransformerNode] && x._2.isEmpty) false else true
    }, "Transformers must have data dependencies")

    require(dataDeps.forall {
      _.filter(_ != Pipeline.SOURCE).forall(x => !nodes(x).isInstanceOf[EstimatorNode])
    }, "Data dependencies may not point at Estimators")
    require(fitDeps.forall {
      _.forall(x => (x != Pipeline.SOURCE) && nodes(x).isInstanceOf[EstimatorNode])
    }, "Fit dependencies may only point at Estimators")

    /*TODO: Validate that
    - there is a data path from sink to in
    - there are no fit paths from sink to in
    */
  }

  final private[workflow] def fitEstimator(node: Int): TransformerNode = fitCache(node).getOrElse {
    nodes(node) match {
      case _: SourceNode =>
        throw new RuntimeException("Pipeline DAG error: Cannot have a fit dependency on a DataNode")
      case _: TransformerNode =>
        throw new RuntimeException("Pipeline DAG error: Cannot have a data dependency on a Transformer")
      case _: DelegatingTransformerNode =>
        throw new RuntimeException("Pipeline DAG error: Cannot have a data dependency on a Transformer")
      case estimator: EstimatorNode =>
        val nodeDataDeps = dataDeps(node).toIterator.map(x => rddDataEval(x, null))
        logInfo(s"Fitting '${estimator.label}' [$node]")
        val fitOut = estimator.fitRDDs(nodeDataDeps)
        fitCache(node) = Some(fitOut)
        logInfo(s"Finished fitting '${estimator.label}' [$node]")
        fitOut
    }
  }

  final private[workflow] def singleDataEval(node: Int, in: A): Any = {
    if (node == Pipeline.SOURCE) {
      in
    } else {
      nodes(node) match {
        case transformer: TransformerNode =>
          val nodeDataDeps = dataDeps(node).toIterator.map(x => singleDataEval(x, in))
          transformer.transform(nodeDataDeps)
        case delTransformer: DelegatingTransformerNode =>
          val nodeFitDep = fitDeps(node).map(fitEstimator).get
          val nodeDataDeps = dataDeps(node).toIterator.map(x => singleDataEval(x, in))
          nodeFitDep.transform(nodeDataDeps)
        case _: SourceNode => throw new RuntimeException(
          "Pipeline DAG error: came across an RDD source dependency when trying to do a single item apply"
        )
        case _: EstimatorNode => throw new RuntimeException(
          "Pipeline DAG error: Cannot have a data dependency on an Estimator"
        )
      }
    }
  }

  final private[workflow] def rddDataEval(node: Int, in: RDD[A]): RDD[_] = {
    if (node == Pipeline.SOURCE) {
      in
    } else {
      dataCache.getOrElse((node, in), {
        nodes(node) match {
          case SourceNode(rdd) => rdd
          case transformer: TransformerNode =>
            val nodeDataDeps = dataDeps(node).toIterator.map(x => rddDataEval(x, in))
            val outputData = transformer.transformRDD(nodeDataDeps)
            dataCache((node, in)) = outputData
            outputData
          case delTransformer: DelegatingTransformerNode =>
            val nodeFitDep = fitDeps(node).map(fitEstimator).get
            val nodeDataDeps = dataDeps(node).toIterator.map(x => rddDataEval(x, in))
            val outputData = nodeFitDep.transformRDD(nodeDataDeps)
            dataCache((node, in)) = outputData
            outputData
          case _: EstimatorNode =>
            throw new RuntimeException("Pipeline DAG error: Cannot have a data dependency on an Estimator")
        }
      })
    }
  }

  override def apply(in: A): B = apply(in, Some(DefaultOptimizer))

  override def apply(in: RDD[A]): RDD[B] = apply(in, Some(DefaultOptimizer))

  def apply(in: A, optimizer: Option[Optimizer]): B = {
    optimizer match {
      case Some(opt) => {
        val optimizedPipeline = optimizedPipelineCache.getOrElseUpdate(opt, opt.execute(this))
        optimizedPipeline.apply(in, None)
      }
      case None => singleDataEval(sink, in).asInstanceOf[B]
    }
  }

  def apply(in: RDD[A], optimizer: Option[Optimizer]): RDD[B] = {
    optimizer match {
      case Some(opt) => {
        val optimizedPipeline = optimizedPipelineCache.getOrElseUpdate(opt, opt.execute(this))
        optimizedPipeline.apply(in, None)
      }
      case None => rddDataEval(sink, in).asInstanceOf[RDD[B]]
    }
  }
}
