import { Component, computed, input } from '@angular/core';

import { WorkflowTemplate, templateStages } from './workflow-templates';

@Component({
  selector: 'app-template-pipeline',
  template: `
    <ol class="pipeline" [attr.aria-label]="'Steps of ' + template().name">
      @for (stage of stages(); track $index) {
        <li class="stage">
          @for (step of stage; track step.key) {
            <span class="step" [attr.data-type]="step.jobType">
              <span class="type">{{ step.jobType }}</span>
              <code>{{ step.key }}</code>
            </span>
          }
        </li>
      }
    </ol>
  `,
  styles: `
    :host {
      display: block;
      container-type: inline-size;
    }

    .pipeline {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 6px 18px;
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .stage {
      position: relative;
      display: flex;
      flex-direction: column;
      gap: 4px;

      & + .stage::before {
        content: '';
        position: absolute;
        left: -14px;
        top: 50%;
        width: 10px;
        border-top: 1.5px solid var(--ff-rule-strong);
      }
    }

    .step {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 3px 8px 3px 4px;
      border: 1px solid var(--ff-rule);
      border-radius: 4px;
      background: var(--ff-panel);
      white-space: nowrap;
    }

    .type {
      padding: 0 4px;
      border-radius: 3px;
      background: var(--ff-wait-wash);
      color: var(--ff-ink-2);
      font-size: 0.625rem;
      font-weight: 700;
      letter-spacing: 0.02em;
    }

    code {
      font-size: 0.75rem;
    }

    @container (max-width: 480px) {
      .pipeline {
        flex-direction: column;
        align-items: flex-start;
        gap: 8px;
      }

      .stage {
        flex-direction: row;
        flex-wrap: wrap;

        & + .stage::before {
          left: 12px;
          top: -8px;
          width: 0;
          height: 8px;
          border-top: 0;
          border-left: 1.5px solid var(--ff-rule-strong);
        }
      }
    }
  `,
})
export class TemplatePipeline {
  readonly template = input.required<WorkflowTemplate>();
  protected readonly stages = computed(() => templateStages(this.template()));
}
