export type UserRole = 'ADMIN' | 'SUPERVISOR' | 'AGENT';

export type UserStatus = 'ACTIVE' | 'INACTIVE' | 'BREAK' | 'AVAILABLE' | 'BUSY' | 'AFTER_CONTACT' | 'OFFLINE';

export interface UserResponse {
  id: string;
  tenantId: string;
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

export interface CreateUserRequest {
  firstName: string;
  lastName: string;
  email: string;
  password: string;
  role: UserRole;
  skills: string[];
}

export interface UpdateUserRequest {
  firstName: string;
  lastName: string;
  role: UserRole;
  skills: string[];
}

export interface UpdateStatusRequest {
  status: UserStatus;
}

export interface UserListParams {
  page: number;
  size: number;
  status?: UserStatus | '';
  skill?: string;
  role?: UserRole | '';
  search?: string;
}

/** Generyczny wrapper paginowanej odpowiedzi – zgodny z backendem PagedResponse<T>. */
export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}
