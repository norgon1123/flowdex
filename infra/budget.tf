# The budget exists so that a mistake cannot quietly bill for a month.
#
# Scope is the whole ACCOUNT, not this stack: AWS budgets filter on cost
# allocation tags, and those take up to 24 hours to activate and are not
# retroactive, so a stack-scoped budget would be blind exactly when a fresh
# mistake needs catching. Account-wide is the right trade for a dedicated
# personal account and the wrong one for a shared account — the deploy
# instructions say so.
resource "aws_budgets_budget" "monthly" {
  name         = "${local.name}-monthly"
  budget_type  = "COST"
  limit_amount = "5"
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # ACTUAL is the truthful signal but a lagging one: Cost Explorer data is
  # 8-24 hours behind, so by the time this fires the money is long spent.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.budget_email]
  }

  # FORECASTED fires on the projected month-end total, which is the only way to
  # hear about a runaway on the day it starts rather than the day after.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.budget_email]
  }
}
