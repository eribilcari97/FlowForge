import { NODE_HEIGHT, NODE_WIDTH, layoutGraph } from './graph-layout';

describe('layoutGraph', () => {
  const diamond = layoutGraph(
    [
      { id: 'a', label: 'a', detail: 'HTTP' },
      { id: 'b', label: 'b', detail: 'HTTP' },
      { id: 'c', label: 'c', detail: 'DELAY' },
      { id: 'd', label: 'd', detail: 'EMAIL' },
    ],
    [
      { from: 'a', to: 'b' },
      { from: 'a', to: 'c' },
      { from: 'b', to: 'd' },
      { from: 'c', to: 'd' },
    ],
  );

  function node(id: string) {
    return diamond.nodes.find((n) => n.id === id)!;
  }

  it('places every step to the right of the steps it waits for', () => {
    expect(node('b').x).toBeGreaterThan(node('a').x);
    expect(node('c').x).toBeGreaterThan(node('a').x);
    expect(node('d').x).toBeGreaterThan(node('b').x);
    expect(node('d').x).toBeGreaterThan(node('c').x);
  });

  it('puts parallel steps in the same column without overlapping', () => {
    expect(node('b').x).toBe(node('c').x);
    expect(Math.abs(node('b').y - node('c').y)).toBeGreaterThanOrEqual(NODE_HEIGHT);
  });

  it('draws one path per dependency and sizes the drawing to fit every node', () => {
    expect(diamond.edges).toHaveLength(4);
    expect(diamond.edges.every((edge) => edge.path.startsWith('M '))).toBe(true);
    for (const n of diamond.nodes) {
      expect(n.width).toBe(NODE_WIDTH);
      expect(n.x + n.width).toBeLessThanOrEqual(diamond.width);
      expect(n.y + n.height).toBeLessThanOrEqual(diamond.height);
    }
  });

  it('ignores dependencies on unknown steps and handles an empty graph', () => {
    const layout = layoutGraph([{ id: 'a', label: 'a', detail: 'HTTP' }], [{ from: 'x', to: 'a' }]);

    expect(layout.nodes).toHaveLength(1);
    expect(layout.edges).toHaveLength(0);
    expect(layoutGraph([], [])).toEqual({ nodes: [], edges: [], width: 0, height: 0 });
  });
});
