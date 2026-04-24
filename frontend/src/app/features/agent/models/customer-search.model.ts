/**
 * Lightweight customer summary returned by GET /api/customers?search=...&limit=20
 * Used exclusively in agent-side customer search (FE-040).
 */
export interface CustomerSummary {
  customerId: string;
  firstName?: string;
  lastName?: string;
  phone: string[];
  email: string[];
  /** ISO 8601 timestamp of the most recent contact; absent if the customer has no contacts yet. */
  lastContactAt?: string;
}

/** Wrapper for the search endpoint response. */
export interface CustomerSearchResponse {
  content: CustomerSummary[];
  totalElements: number;
}
