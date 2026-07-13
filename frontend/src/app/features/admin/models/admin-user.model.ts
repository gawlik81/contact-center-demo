import { UserRole, UserStatus } from '../../supervisor/models/user.model';

export interface AdminUserResponse {
  id: string;
  /** Null dla SUPER_ADMIN (globalny administrator platformy, bez przypisania do tenanta). */
  tenantId: string | null;
  email: string;
  firstName: string;
  lastName: string;
  role: UserRole;
  status: UserStatus;
  active: boolean;
  skills: string[];
  passwordResetRequired: boolean;
  mfaEnabled: boolean;
  lastLoginAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AdminCreateUserRequest {
  /** Wymagany dla wszystkich ról poza SUPER_ADMIN (globalny, bez przypisania do tenanta). */
  tenantId: string | null;
  email: string;
  password: string;
  firstName: string;
  lastName: string;
  role: UserRole;
  skills: string[];
}

export interface AdminUpdateUserRequest {
  firstName?: string | null;
  lastName?: string | null;
  email?: string | null;
  role?: UserRole | null;
  skills?: string[] | null;
  active?: boolean | null;
}

export interface AdminUserListParams {
  page: number;
  size: number;
  tenantId?: string;
}

export interface AdminPagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}
