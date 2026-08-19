terraform {
  required_version = ">= 1.9"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
  # State stays local and gitignored. Remote state with a lock table is right
  # for a team and wrong for a solo project that would need bootstrap
  # infrastructure to hold state for a handful of resources.
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      project = "flowdex"
    }
  }
}
