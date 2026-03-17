import { Injectable } from '@angular/core';
import { JwtPayload } from '../models/jwt-payload.model';

const REFRESH_TOKEN_KEY = 'refresh_token';

@Injectable({ providedIn: 'root' })
export class TokenService {
  // Access token: in-memory only — never persisted to localStorage (XSS mitigation).
  // On page reload the token is lost; AuthService performs a silent refresh via /auth/refresh
  // using the refresh token stored in sessionStorage.
  private accessTokenMemory: string | null = null;

  setAccessToken(token: string): void {
    this.accessTokenMemory = token;
  }

  getAccessToken(): string | null {
    return this.accessTokenMemory;
  }

  clearAccessToken(): void {
    this.accessTokenMemory = null;
  }

  // Refresh token: sessionStorage (clears on tab/browser close)
  setRefreshToken(token: string): void {
    sessionStorage.setItem(REFRESH_TOKEN_KEY, token);
  }

  getRefreshToken(): string | null {
    return sessionStorage.getItem(REFRESH_TOKEN_KEY);
  }

  clearRefreshToken(): void {
    sessionStorage.removeItem(REFRESH_TOKEN_KEY);
  }

  clearAll(): void {
    this.clearAccessToken();
    this.clearRefreshToken();
  }

  /**
   * Decodes JWT payload without verifying signature (verification is done server-side).
   * Uses base64 decode of the payload segment.
   */
  decodePayload(token: string): JwtPayload | null {
    try {
      const parts = token.split('.');
      if (parts.length !== 3) {
        return null;
      }
      const payload = parts[1];
      // Pad base64url string to standard base64
      const padded = payload.replace(/-/g, '+').replace(/_/g, '/');
      const padding = padded.length % 4 === 0 ? '' : '='.repeat(4 - (padded.length % 4));
      const decoded = atob(padded + padding);
      return JSON.parse(decoded) as JwtPayload;
    } catch {
      return null;
    }
  }

  isTokenExpired(token: string): boolean {
    const payload = this.decodePayload(token);
    if (!payload) {
      return true;
    }
    // exp is in seconds, Date.now() in milliseconds
    return payload.exp * 1000 < Date.now();
  }
}
