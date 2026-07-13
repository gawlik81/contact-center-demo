export type UserRole = 'SUPER_ADMIN' | 'ADMIN' | 'SUPERVISOR' | 'AGENT';

export interface JwtPayload {
  sub: string;
  iss: string;
  iat: number;
  exp: number;
  tenant_id?: string;
  tenant_name?: string;
  user_id: string;
  role: UserRole;
  email: string;
  mfaVerified: boolean;
}
