import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DependencyGraph } from './dependency-graph';
import { GraphEdgeInput, GraphNodeInput } from './graph-layout';

describe('DependencyGraph', () => {
  let fixture: ComponentFixture<DependencyGraph>;
  let element: HTMLElement;
  let clicked: string[];

  async function render(nodes: GraphNodeInput[], edges: GraphEdgeInput[], clickable: boolean) {
    TestBed.configureTestingModule({ imports: [DependencyGraph] });
    fixture = TestBed.createComponent(DependencyGraph);
    fixture.componentRef.setInput('nodes', nodes);
    fixture.componentRef.setInput('edges', edges);
    fixture.componentRef.setInput('clickable', clickable);
    clicked = [];
    fixture.componentInstance.nodeClick.subscribe((id) => clicked.push(id));
    element = fixture.nativeElement;
    await fixture.whenStable();
  }

  const nodes: GraphNodeInput[] = [
    { id: 'fetch_orders', label: 'fetch_orders', detail: 'HTTP · SUCCEEDED', status: 'SUCCEEDED' },
    {
      id: 'calculate_revenue',
      label: 'calculate_revenue',
      detail: 'TRANSFORM · FAILED',
      status: 'FAILED',
    },
    {
      id: 'send_report_to_the_whole_team',
      label: 'send_report_to_the_whole_team',
      detail: 'EMAIL · SKIPPED',
      status: 'SKIPPED',
    },
  ];
  const edges: GraphEdgeInput[] = [
    { from: 'fetch_orders', to: 'calculate_revenue' },
    { from: 'calculate_revenue', to: 'send_report_to_the_whole_team' },
  ];

  it('draws one node per step with its status and one arrow per dependency', async () => {
    await render(nodes, edges, false);

    const drawn = [...element.querySelectorAll('g.graph-node')];
    expect(drawn.map((node) => node.getAttribute('data-status'))).toEqual([
      'SUCCEEDED',
      'FAILED',
      'SKIPPED',
    ]);
    expect(element.querySelectorAll('path.graph-edge')).toHaveLength(2);
    expect(element.querySelector('svg')?.getAttribute('aria-label')).toContain('3 steps');
  });

  it('shortens long keys but keeps the full text in the tooltip', async () => {
    await render(nodes, edges, false);

    const last = element.querySelectorAll('g.graph-node')[2];
    expect(last.querySelector('.graph-label')?.textContent).toBe('send_report_to_the_wh…');
    expect(last.querySelector('title')?.textContent).toContain('send_report_to_the_whole_team');
  });

  it('reports clicks only when nodes are clickable', async () => {
    await render(nodes, edges, true);
    (element.querySelectorAll('g.graph-node')[1] as SVGGElement).dispatchEvent(
      new MouseEvent('click'),
    );
    expect(clicked).toEqual(['calculate_revenue']);

    TestBed.resetTestingModule();
    await render(nodes, edges, false);
    (element.querySelectorAll('g.graph-node')[1] as SVGGElement).dispatchEvent(
      new MouseEvent('click'),
    );
    expect(clicked).toEqual([]);
  });

  it('says so when there is nothing to draw', async () => {
    await render([], [], false);

    expect(element.querySelector('svg')).toBeNull();
    expect(element.textContent).toContain('Nothing to show yet.');
  });
});
