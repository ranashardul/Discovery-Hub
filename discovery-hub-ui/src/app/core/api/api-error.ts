/**
 * Transport-agnostic error. The mock backend throws these with the same status
 * codes the Spring services return, so error handling in components does not
 * have to change when the real APIs are wired in.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly field?: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }

  static badRequest(message: string, field?: string): ApiError {
    return new ApiError(400, message, field);
  }

  static notFound(message: string): ApiError {
    return new ApiError(404, message);
  }

  /** Used for lifecycle and read-only violations, mirroring the case service. */
  static conflict(message: string): ApiError {
    return new ApiError(409, message);
  }
}

export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return 'Unexpected error';
}
