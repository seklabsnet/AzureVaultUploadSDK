import { HttpResponseInit } from "@azure/functions";
import { AppError } from "./errors.js";

export function success(data: unknown, status = 200): HttpResponseInit {
  return {
    status,
    jsonBody: { success: true, data },
  };
}

export function error(err: AppError | Error): HttpResponseInit {
  if (err instanceof AppError) {
    return {
      status: err.statusCode,
      jsonBody: { success: false, error: { code: err.code, message: err.message, details: err.details } },
    };
  }
  return {
    status: 500,
    jsonBody: { success: false, error: { code: "INTERNAL_ERROR", message: "An unexpected error occurred" } },
  };
}
