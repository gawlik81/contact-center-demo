export interface Queue {
  id: string;
  tenantId: string;
  name: string;
  description?: string;
  routingStrategy: string;
  requiredSkills: string[];
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateQueueRequest {
  name: string;
  routingStrategy: string;
  requiredSkills?: string[];
}

export interface UpdateQueueRequest {
  name?: string;
  routingStrategy?: string;
  requiredSkills?: string[];
  isActive?: boolean;
}
