export interface Queue {
  queueId: string;
  tenantId: string;
  name: string;
  description?: string;
  routingStrategy: string;
  requiredSkills: string[];
  emailAddress?: string | null;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateQueueRequest {
  name: string;
  routingStrategy: string;
  requiredSkills?: string[];
  emailAddress?: string | null;
}

export interface UpdateQueueRequest {
  name?: string;
  routingStrategy?: string;
  requiredSkills?: string[];
  emailAddress?: string | null;
  active?: boolean;
}
