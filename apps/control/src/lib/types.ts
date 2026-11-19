export interface Codespace { name: string; displayName: string | null; state: string; repository: string; branch: string | null; machine: string | null;
  lastUsedAt: string | null; idleTimeoutMinutes: number | null; webUrl: string | null; retentionExpiresAt: string | null }
export interface Session { id: string; codespaceName: string; state: string; requestedAt: string; runningAt: string | null; deadlineAt: string;
  stopReason: string | null; stopAttempts: number; lastGithubState: string | null; lastError: string | null; endedAt: string | null; startedBy: string | null }
export interface Overview { configured: boolean; settings: { selectedCodespace: string | null; maxSessionHours: number; repository: string | null };
  codespace?: Codespace | null; error?: string | null; session?: Session | null; now?: string }
export interface ApiStatus { reachable: boolean; reason?: string; checkedAt: string; data?: {
  status: string; gitCommit: string | null; startedAt: string | null; database: { up: boolean; latencyMs?: number; flywayVersion?: string | null };
  redis: { up: boolean; error?: string }; workers: { enabled: boolean; outboxPending: number; jobsPending: number; jobsDead: number; messagesQueued: number; automationDue: number; deadLetters: number };
  recentErrors: { at: string; source: string; message: string }[] } }
export interface LogRow { category: string; level: string; message: string; createdAt: string }
