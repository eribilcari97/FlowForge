import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { problemOf } from '../../core/api/problem';
import { AuthService } from '../../core/auth/auth.service';
import { AuthIntro } from './auth-intro';
import { safeReturnUrl } from './return-url';

@Component({
  selector: 'app-login',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    AuthIntro,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
  ],
  styleUrl: './auth-page.scss',
  template: `
    <div class="auth">
      <app-auth-intro />
      <section class="auth-form">
        <h1>Log in</h1>
        <form [formGroup]="form" (ngSubmit)="submit()">
          <mat-form-field>
            <mat-label>Email</mat-label>
            <input matInput type="email" formControlName="email" autocomplete="username" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Password</mat-label>
            <input
              matInput
              type="password"
              formControlName="password"
              autocomplete="current-password"
            />
          </mat-form-field>
          @if (error(); as error) {
            <p class="form-error" role="alert">{{ error }}</p>
          }
          <button mat-flat-button class="submit" type="submit" [disabled]="submitting()">
            Log in
          </button>
        </form>
        <p class="switch">
          New to FlowForge?
          <a routerLink="/register" queryParamsHandling="preserve">Create an account</a>
        </p>
      </section>
    </div>
  `,
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    const { email, password } = this.form.getRawValue();
    this.auth.login(email, password).subscribe({
      next: () =>
        this.router.navigateByUrl(
          safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl')),
        ),
      error: (error: unknown) => {
        this.submitting.set(false);
        const code = problemOf(error)?.code;
        this.error.set(
          code === 'INVALID_CREDENTIALS'
            ? 'Invalid email or password.'
            : code === 'TOO_MANY_REQUESTS'
              ? 'Too many attempts. Wait a minute and try again.'
              : 'Login failed. Please try again.',
        );
      },
    });
  }
}
