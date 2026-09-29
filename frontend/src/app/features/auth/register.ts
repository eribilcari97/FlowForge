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
  selector: 'app-register',
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
        <h1>Create an account</h1>
        <form [formGroup]="form" (ngSubmit)="submit()">
          <mat-form-field>
            <mat-label>Email</mat-label>
            <input matInput type="email" formControlName="email" autocomplete="username" />
            @if (form.controls.email.hasError('email')) {
              <mat-error>Enter a valid email address</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Display name</mat-label>
            <input matInput formControlName="displayName" autocomplete="name" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Password</mat-label>
            <input
              matInput
              type="password"
              formControlName="password"
              autocomplete="new-password"
            />
            <mat-hint>10 to 72 characters</mat-hint>
            @if (
              form.controls.password.hasError('minlength') ||
              form.controls.password.hasError('maxlength')
            ) {
              <mat-error>Use 10 to 72 characters</mat-error>
            }
          </mat-form-field>
          @for (message of errors(); track message) {
            <p class="form-error" role="alert">{{ message }}</p>
          }
          <button mat-flat-button class="submit" type="submit" [disabled]="submitting()">
            Create account
          </button>
        </form>
        <p class="switch">
          Already have an account?
          <a routerLink="/login" queryParamsHandling="preserve">Log in</a>
        </p>
      </section>
    </div>
  `,
})
export class Register {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    displayName: ['', [Validators.required, Validators.maxLength(100)]],
    password: ['', [Validators.required, Validators.minLength(10), Validators.maxLength(72)]],
  });
  protected readonly submitting = signal(false);
  protected readonly errors = signal<string[]>([]);

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.errors.set([]);
    this.auth.register(this.form.getRawValue()).subscribe({
      next: () =>
        this.router.navigateByUrl(
          safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl')),
        ),
      error: (error: unknown) => {
        this.submitting.set(false);
        const problem = problemOf(error);
        if (problem?.code === 'EMAIL_TAKEN') {
          this.errors.set(['An account with this email already exists.']);
        } else if (problem?.errors?.length) {
          this.errors.set(problem.errors.map((e) => `${e.field}: ${e.message}`));
        } else {
          this.errors.set(['Registration failed. Please try again.']);
        }
      },
    });
  }
}
