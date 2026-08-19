resource "aws_api_gateway_rest_api" "api" {
  name = local.name

  # REST API, not HTTP API: API keys and usage plans are a REST feature.
  # HTTP API would need a Lambda authorizer to gate access, which is more
  # moving parts than the requirement justifies.

  # gzip request bodies arrive as binary and are base64-encoded to the handler.
  binary_media_types = ["application/gzip", "application/octet-stream"]

  endpoint_configuration {
    types = ["REGIONAL"]
  }
}

resource "aws_api_gateway_resource" "ingest" {
  rest_api_id = aws_api_gateway_rest_api.api.id
  parent_id   = aws_api_gateway_rest_api.api.root_resource_id
  path_part   = "ingest"
}

resource "aws_api_gateway_resource" "connections" {
  rest_api_id = aws_api_gateway_rest_api.api.id
  parent_id   = aws_api_gateway_rest_api.api.root_resource_id
  path_part   = "connections"
}

resource "aws_api_gateway_resource" "ip" {
  rest_api_id = aws_api_gateway_rest_api.api.id
  parent_id   = aws_api_gateway_rest_api.api.root_resource_id
  path_part   = "ip"
}

resource "aws_api_gateway_resource" "addr" {
  rest_api_id = aws_api_gateway_rest_api.api.id
  parent_id   = aws_api_gateway_resource.ip.id
  path_part   = "{addr}"
}

resource "aws_api_gateway_resource" "summary" {
  rest_api_id = aws_api_gateway_rest_api.api.id
  parent_id   = aws_api_gateway_resource.addr.id
  path_part   = "summary"
}

locals {
  routes = {
    ingest = {
      resource_id = aws_api_gateway_resource.ingest.id
      method      = "POST"
      function    = "ingest"
    }
    connections = {
      resource_id = aws_api_gateway_resource.connections.id
      method      = "GET"
      function    = "connections"
    }
    summary = {
      resource_id = aws_api_gateway_resource.summary.id
      method      = "GET"
      function    = "summary"
    }
  }
}

resource "aws_api_gateway_method" "route" {
  for_each         = local.routes
  rest_api_id      = aws_api_gateway_rest_api.api.id
  resource_id      = each.value.resource_id
  http_method      = each.value.method
  authorization    = "NONE"
  api_key_required = true
}

resource "aws_api_gateway_integration" "route" {
  for_each                = local.routes
  rest_api_id             = aws_api_gateway_rest_api.api.id
  resource_id             = each.value.resource_id
  http_method             = aws_api_gateway_method.route[each.key].http_method
  type                    = "AWS_PROXY"
  integration_http_method = "POST"
  uri                     = aws_lambda_alias.live[each.value.function].invoke_arn
  timeout_milliseconds    = 29000
}

resource "aws_api_gateway_deployment" "api" {
  rest_api_id = aws_api_gateway_rest_api.api.id

  # Redeploy whenever any route or integration changes.
  triggers = {
    redeploy = sha1(jsonencode([
      aws_api_gateway_method.route,
      aws_api_gateway_integration.route,
    ]))
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_api_gateway_stage" "prod" {
  rest_api_id   = aws_api_gateway_rest_api.api.id
  deployment_id = aws_api_gateway_deployment.api.id
  stage_name    = "prod"
}

resource "aws_api_gateway_api_key" "key" {
  name    = "${local.name}-key"
  enabled = true
}

resource "aws_api_gateway_usage_plan" "plan" {
  name = "${local.name}-plan"

  api_stages {
    api_id = aws_api_gateway_rest_api.api.id
    stage  = aws_api_gateway_stage.prod.stage_name
  }

  quota_settings {
    limit  = 10000
    period = "DAY"
  }

  throttle_settings {
    rate_limit  = 20
    burst_limit = 40
  }
}

resource "aws_api_gateway_usage_plan_key" "key" {
  key_id        = aws_api_gateway_api_key.key.id
  key_type      = "API_KEY"
  usage_plan_id = aws_api_gateway_usage_plan.plan.id
}
