output "api_url" {
  description = "Base URL of the deployed API"
  value       = aws_api_gateway_stage.prod.invoke_url
}

output "api_key_id" {
  description = "Read the secret with: aws apigateway get-api-key --api-key <id> --include-value"
  value       = aws_api_gateway_api_key.key.id
}

output "bucket" {
  description = "Raw batch bucket; empty this before terraform destroy"
  value       = aws_s3_bucket.raw.id
}

output "table" {
  value = aws_dynamodb_table.index.name
}
