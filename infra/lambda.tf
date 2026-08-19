locals {
  # Handler, size and timeout together, because they are not independent: the
  # write path is I/O-fanned-out and allowed to outlive the gateway, the read
  # paths are single-query and are not.
  functions = {
    ingest = {
      handler = "dev.orgon.flowdex.handler.IngestHandler"
      # 2048 MB is ~1.15 vCPU (Lambda reaches 1 vCPU at 1769 MB). The writer
      # pool is I/O-bound, but TLS handshakes and JSON marshalling for 32
      # concurrent transactions are not, and below one full core they serialise.
      memory = 2048
      # Deliberately longer than the gateway's 29 s, which looks like waste and
      # is not. If both die at 29 s, indexing stops mid-flight and every 504
      # leaves a half-indexed batch the client must re-POST from scratch.
      # Letting the function run on means the 504'd request still COMPLETES in
      # the background, so the client's retry reports duplicates and does no
      # work. Idempotency is what makes that safe; without it this would be a
      # double-counting bug. The cost of the extra seconds is a fraction of a
      # cent, and only on batches that were already too big.
      timeout = 120
    }
    connections = {
      handler = "dev.orgon.flowdex.handler.ConnectionsHandler"
      memory  = 1024
      # A read that outlives the gateway has nobody left to answer, so it only
      # burns money. Matching the gateway is right here and wrong for ingest.
      timeout = 29
    }
    summary = {
      handler = "dev.orgon.flowdex.handler.SummaryHandler"
      memory  = 1024
      timeout = 29
    }
  }

  # Least privilege is per function, not per stack. The two read handlers have
  # no reason to hold a write grant, and a reviewer checking whether "scoped"
  # means anything looks here first.
  function_table_actions = {
    # Writes go through TransactWriteItems, which has no IAM action of its own:
    # transactions are authorised by the underlying item operations, so a
    # conditional Put plus a rollup Update needs exactly PutItem and UpdateItem.
    # Only a ConditionCheck transact item would additionally need
    # dynamodb:ConditionCheckItem, and this transaction does not use one.
    ingest      = ["dynamodb:PutItem", "dynamodb:UpdateItem"]
    connections = ["dynamodb:Query"]
    summary     = ["dynamodb:Query"]
  }
}

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "lambda" {
  for_each           = local.functions
  name               = "${local.name}-${each.key}"
  assume_role_policy = data.aws_iam_policy_document.assume.json
}

# Scoped to exactly the one table, the one bucket prefix, the one log group —
# and, per function, to exactly the operations that function performs.
data "aws_iam_policy_document" "lambda" {
  for_each = local.functions

  statement {
    actions   = local.function_table_actions[each.key]
    resources = [aws_dynamodb_table.index.arn]
  }

  # Only the write path puts raw batches. Nothing in the stack reads them back:
  # provenance is redeemed with your own credentials, outside the API.
  dynamic "statement" {
    for_each = each.key == "ingest" ? [1] : []
    content {
      actions   = ["s3:PutObject"]
      resources = ["${aws_s3_bucket.raw.arn}/*"]
    }
  }

  # No logs:CreateLogGroup, deliberately: the groups are Terraform-managed with
  # a retention policy, so a function that cannot create one cannot leave an
  # unretained group behind after destroy.
  statement {
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.lambda[each.key].arn}:*"]
  }
}

resource "aws_iam_role_policy" "lambda" {
  for_each = local.functions
  role     = aws_iam_role.lambda[each.key].id
  policy   = data.aws_iam_policy_document.lambda[each.key].json
}

resource "aws_cloudwatch_log_group" "lambda" {
  for_each          = local.functions
  name              = "/aws/lambda/${local.name}-${each.key}"
  retention_in_days = var.log_retention_days
}

resource "aws_lambda_function" "fn" {
  for_each = local.functions

  function_name    = "${local.name}-${each.key}"
  role             = aws_iam_role.lambda[each.key].arn
  handler          = each.value.handler
  runtime          = "java21"
  filename         = var.jar_path
  source_code_hash = filebase64sha256(var.jar_path)
  memory_size      = each.value.memory
  timeout          = each.value.timeout

  # Stated rather than defaulted. x86_64 is already the default, but SnapStart
  # for Java does not support arm64, so the value is a constraint of the design
  # and not a preference — pinning it stops a future "let's try Graviton" from
  # silently disabling the thing the cold-start story rests on.
  architectures = ["x86_64"]

  # SnapStart operates on published versions, so the function must publish one
  # and the API must invoke an alias — never $LATEST.
  publish = true

  snap_start {
    apply_on = "PublishedVersions"
  }

  environment {
    variables = {
      TABLE_NAME  = aws_dynamodb_table.index.name
      BUCKET_NAME = aws_s3_bucket.raw.id
    }
  }

  depends_on = [aws_cloudwatch_log_group.lambda, aws_iam_role_policy.lambda]
}

resource "aws_lambda_alias" "live" {
  for_each         = local.functions
  name             = "live"
  function_name    = aws_lambda_function.fn[each.key].function_name
  function_version = aws_lambda_function.fn[each.key].version
}

resource "aws_lambda_permission" "apigw" {
  for_each      = local.functions
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.fn[each.key].function_name
  qualifier     = aws_lambda_alias.live[each.key].name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.api.execution_arn}/*/*"
}
