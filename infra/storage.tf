resource "aws_s3_bucket" "raw" {
  bucket = local.bucket

  # Deliberately not force_destroy: data must never be removed as a side
  # effect of a destroy. Teardown documents emptying the bucket first.
}

resource "aws_s3_bucket_public_access_block" "raw" {
  bucket                  = aws_s3_bucket.raw.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "raw" {
  bucket = aws_s3_bucket.raw.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# No aws_s3_bucket_versioning resource, deliberately. "Suspended" is the state a
# bucket that HAS been versioned returns to; a bucket that never had versioning
# enabled is already "Disabled", and declaring Suspended only adds an API call
# that changes nothing. Raw batches are write-once and never mutated, so there
# is nothing for versioning to protect.

resource "aws_dynamodb_table" "index" {
  name         = local.name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"
  range_key    = "SK"

  attribute {
    name = "PK"
    type = "S"
  }
  attribute {
    name = "SK"
    type = "S"
  }
}
