export interface FieldError { field: string | null; message: string }

/** A problem+json error from the API (or a network failure). */
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public errors: FieldError[] = []) {
    super(message);
  }

  /** Message for one form field, if the server rejected it. */
  field(name: string): string | undefined {
    return this.errors.find((e) => e.field === name)?.message;
  }
}

export const errorMessage = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong');
