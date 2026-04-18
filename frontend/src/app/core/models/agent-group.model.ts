export interface AgentGroup {
  groupId: string;
  name: string;
  memberCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface AgentGroupMember {
  agentId: string;
  firstName: string;
  lastName: string;
  email: string;
}

export interface AgentGroupMembers {
  groupId: string;
  groupName: string;
  members: AgentGroupMember[];
}

export interface AgentGroupSummary {
  groupId: string;
  name: string;
  memberCount: number;
}

export interface QueueAssignment {
  queueId: string;
  allAgents: boolean;
  directAgents: AgentGroupMember[];
  groups: AgentGroupSummary[];
}

export interface CreateAgentGroupRequest {
  name: string;
}

export interface UpdateAgentGroupRequest {
  name: string;
}

export interface ReplaceGroupMembersRequest {
  agentIds: string[];
}

export interface UpdateQueueAssignmentRequest {
  allAgents: boolean;
  directAgentIds: string[];
  groupIds: string[];
}
