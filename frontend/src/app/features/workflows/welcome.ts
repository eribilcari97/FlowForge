import { Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';

import { WORKFLOW_TEMPLATES } from './workflow-templates';

@Component({
  selector: 'app-welcome',
  imports: [RouterLink, MatButtonModule],
  template: `
    <section class="intro" aria-labelledby="welcome-title">
      <h1 id="welcome-title">No workflows yet</h1>
      <p>
        Workflows automate repetitive tasks for you. Each one is a short list of steps, like calling
        an API or sending an email, that FlowForge runs on a schedule or whenever you start it.
      </p>
      <a mat-flat-button class="create" routerLink="/workflows/new">Create workflow</a>
    </section>

    <section aria-labelledby="templates-title">
      <h2 id="templates-title">Or start from a template</h2>
      <ul class="templates">
        @for (template of templates; track template.id) {
          <li>
            <a
              class="template"
              routerLink="/workflows/new"
              [queryParams]="{ template: template.id }"
            >
              <span class="name">{{ template.name }}</span>
              <span class="desc">{{ template.description }}</span>
              <span class="trigger">{{ template.trigger }}</span>
              <span class="use">Use template <span aria-hidden="true">→</span></span>
            </a>
          </li>
        }
      </ul>
    </section>
  `,
  styles: `
    :host {
      display: block;
      max-width: 1000px;
    }

    .intro {
      padding: 40px 0 36px;
      border-bottom: 1px solid var(--ff-rule);

      p {
        max-width: 56ch;
        margin: 12px 0 24px;
        color: var(--ff-ink-2);
        font-size: 1.0625rem;
        line-height: 1.55;
      }
    }

    h2 {
      margin: 32px 0 16px;
      font-size: 1rem;
    }

    .templates {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
      gap: 12px;
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .template {
      display: flex;
      flex-direction: column;
      gap: 6px;
      height: 100%;
      box-sizing: border-box;
      padding: 16px;
      background: var(--ff-panel);
      border: 1px solid var(--ff-rule);
      border-radius: var(--ff-radius);
      color: inherit;
      text-decoration: none;

      &:hover {
        border-color: var(--ff-ink);
      }
    }

    .name {
      font-weight: 700;
    }

    .desc,
    .trigger {
      color: var(--ff-ink-2);
      font-size: 0.8125rem;
    }

    .use {
      margin-top: auto;
      padding-top: 8px;
      font-size: 0.8125rem;
      font-weight: 700;
    }
  `,
})
export class Welcome {
  protected readonly templates = WORKFLOW_TEMPLATES;
}
