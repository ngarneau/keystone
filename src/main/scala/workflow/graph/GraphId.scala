package workflow.graph

/**
 * This is a unifying type for Node, Source, and Sink ids in the
 * internal graph data structure representing workloads.
 */
private[graph] sealed trait GraphId

/**
 * This represents the id of a Sink in the internal graph data structure.
 * @param id The internal value, unique to each id
 */
private[graph] case class SinkId(id: Long) extends GraphId

/**
 * This is a unifying type for Node and Source ids in the
 * internal graph data structure representing workloads.
 */
private[graph] sealed trait NodeOrSourceId extends GraphId

/**
 * This represents the id of a Node in the internal graph data structure.
 * @param id The internal value, unique to each id
 */
private[graph] case class NodeId(id: Long) extends NodeOrSourceId

/**
 * This represents the id of a Source in the internal graph data structure.
 * @param id The internal value, unique to each id
 */
private[graph] case class SourceId(id: Long) extends NodeOrSourceId
