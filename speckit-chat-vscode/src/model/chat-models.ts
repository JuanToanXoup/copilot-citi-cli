export enum ChatRunStatus {
    RUNNING = 'RUNNING',
    COMPLETED = 'COMPLETED',
    FAILED = 'FAILED',
    CANCELLED = 'CANCELLED',
}

export class ChatRun {
    status: ChatRunStatus = ChatRunStatus.RUNNING;
    durationMs = 0;
    sessionId: string | undefined;
    errorMessage: string | undefined;

    constructor(
        public readonly agent: string,
        public readonly prompt: string,
        public readonly branch: string,
        public readonly startTimeMillis: number = Date.now(),
    ) {}
}
