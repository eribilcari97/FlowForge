import dagre from '@dagrejs/dagre';

export interface GraphNodeInput {
  id: string;
  label: string;
  detail: string;
  status?: string;
}

export interface GraphEdgeInput {
  from: string;
  to: string;
}

export interface PositionedNode extends GraphNodeInput {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface PositionedEdge extends GraphEdgeInput {
  path: string;
}

export interface GraphLayout {
  nodes: PositionedNode[];
  edges: PositionedEdge[];
  width: number;
  height: number;
}

export const NODE_WIDTH = 180;
export const NODE_HEIGHT = 56;
const MARGIN = 16;

export function layoutGraph(nodes: GraphNodeInput[], edges: GraphEdgeInput[]): GraphLayout {
  if (nodes.length === 0) {
    return { nodes: [], edges: [], width: 0, height: 0 };
  }
  const graph = new dagre.graphlib.Graph();
  graph.setGraph({ rankdir: 'LR', nodesep: 24, ranksep: 64, marginx: MARGIN, marginy: MARGIN });
  graph.setDefaultEdgeLabel(() => ({}));
  for (const node of nodes) {
    graph.setNode(node.id, { width: NODE_WIDTH, height: NODE_HEIGHT });
  }
  const ids = new Set(nodes.map((node) => node.id));
  const knownEdges = edges.filter((edge) => ids.has(edge.from) && ids.has(edge.to));
  for (const edge of knownEdges) {
    graph.setEdge(edge.from, edge.to);
  }

  dagre.layout(graph);

  const positioned = nodes.map((node) => {
    const { x, y } = graph.node(node.id);
    return {
      ...node,
      x: x - NODE_WIDTH / 2,
      y: y - NODE_HEIGHT / 2,
      width: NODE_WIDTH,
      height: NODE_HEIGHT,
    };
  });
  const positionedEdges = knownEdges.map((edge) => ({
    ...edge,
    path: toPath(graph.edge(edge.from, edge.to).points),
  }));
  const size = graph.graph();
  return {
    nodes: positioned,
    edges: positionedEdges,
    width: Math.ceil(size.width ?? 0),
    height: Math.ceil(size.height ?? 0),
  };
}

function toPath(points: { x: number; y: number }[]): string {
  return points
    .map((point, index) => `${index === 0 ? 'M' : 'L'} ${round(point.x)} ${round(point.y)}`)
    .join(' ');
}

function round(value: number): number {
  return Math.round(value * 10) / 10;
}
