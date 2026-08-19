data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

resource "random_id" "suffix" {
  byte_length = 4
}

locals {
  name   = var.name_prefix
  bucket = "${var.name_prefix}-raw-${random_id.suffix.hex}"
}
