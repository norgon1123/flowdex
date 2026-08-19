variable "region" {
  description = "AWS region to deploy into"
  type        = string
  default     = "us-east-1"
}

variable "name_prefix" {
  description = "Prefix for all resource names"
  type        = string
  default     = "flowdex"
}

variable "budget_email" {
  description = "Address notified when the monthly budget reaches 80%"
  type        = string
}

variable "jar_path" {
  description = "Path to the shaded handler jar"
  type        = string
  default     = "../target/flowdex.jar"
}

variable "log_retention_days" {
  description = "CloudWatch Logs retention"
  type        = number
  default     = 7
}
