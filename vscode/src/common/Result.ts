export enum Result {
  SUCCESS = "SUCCESS",
  FAILURE = "FAILURE",
  PENDING = "PENDING",
  NONE = "NONE",
}

export interface ResultWrapper<T> {
  result: Result;
  data?: T;
}
