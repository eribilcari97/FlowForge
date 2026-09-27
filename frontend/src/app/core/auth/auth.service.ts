import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, map, switchMap, tap } from 'rxjs';

export interface User {
  id: number;
  email: string;
  displayName: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  displayName: string;
}

interface LoginResponse {
  accessToken: string;
  expiresIn: number;
  user: User;
}

interface Session {
  token: string;
  expiresAt: number;
  user: User;
}

const STORAGE_KEY = 'flowforge.session';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly session = signal<Session | null>(readStoredSession());

  readonly user = computed(() => this.session()?.user ?? null);

  token(): string | null {
    const session = this.session();
    if (session && session.expiresAt <= Date.now()) {
      this.clearSession();
      return null;
    }
    return session?.token ?? null;
  }

  isAuthenticated(): boolean {
    return this.token() !== null;
  }

  login(email: string, password: string): Observable<User> {
    return this.http.post<LoginResponse>('/api/auth/login', { email, password }).pipe(
      tap((response) =>
        this.storeSession({
          token: response.accessToken,
          expiresAt: Date.now() + response.expiresIn * 1000,
          user: response.user,
        }),
      ),
      map((response) => response.user),
    );
  }

  register(request: RegisterRequest): Observable<User> {
    return this.http
      .post<User>('/api/auth/register', request)
      .pipe(switchMap(() => this.login(request.email, request.password)));
  }

  logout(returnUrl?: string): void {
    this.clearSession();
    this.router.navigate(['/login'], { queryParams: returnUrl ? { returnUrl } : {} });
  }

  private storeSession(session: Session): void {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    this.session.set(session);
  }

  private clearSession(): void {
    localStorage.removeItem(STORAGE_KEY);
    this.session.set(null);
  }
}

function readStoredSession(): Session | null {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    const session = stored ? (JSON.parse(stored) as Session) : null;
    return session && session.expiresAt > Date.now() ? session : null;
  } catch {
    return null;
  }
}
