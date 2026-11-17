"""Offline tests of the actual inline Lambda; no boto3 or YAML dependency required."""

import copy
import json
import os
from pathlib import Path
import sys
import textwrap
import types
import unittest
from unittest.mock import Mock, patch


TEMPLATE_PATH = Path(__file__).resolve().parents[1] / "database-rotation.yaml"
TEMPLATE = TEMPLATE_PATH.read_text(encoding="utf-8")
SECRET_ARN = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:test-AbCdEf"
SERVICE_ARN = "arn:aws:ecs:ap-southeast-1:123456789012:service/test/service"
NOW = 1800000000


class ConditionalCheckFailed(Exception):
    pass


class Condition:
    def __init__(self, predicate):
        self.predicate = predicate

    def __and__(self, other):
        return Condition(lambda item: self.predicate(item) and other.predicate(item))

    def __or__(self, other):
        return Condition(lambda item: self.predicate(item) or other.predicate(item))


class Attr:
    """Evaluate the resource-interface conditions against an in-memory item."""

    def __init__(self, name):
        self.name = name

    def eq(self, value):
        return Condition(lambda item: item.get(self.name) == value)

    def lte(self, value):
        return Condition(lambda item: self.name in item and item[self.name] <= value)

    def not_exists(self):
        return Condition(lambda item: self.name not in item)


class FakeTable:
    def __init__(self):
        self.items = {}
        self.current_key = None
        self.writes = []
        self.reads = []
        self.meta = types.SimpleNamespace(
            client=types.SimpleNamespace(
                exceptions=types.SimpleNamespace(
                    ConditionalCheckFailedException=ConditionalCheckFailed
                )
            )
        )

    @property
    def item(self):
        return self.items.get(self.current_key, {})

    @item.setter
    def item(self, item):
        self.current_key = item["versionKey"]
        self.items[self.current_key] = item

    def put_item(self, *, Item, ConditionExpression):
        if not ConditionExpression.predicate(self.items.get(Item["versionKey"], {})):
            raise ConditionalCheckFailed()
        self.item = copy.deepcopy(Item)
        self.writes.append(copy.deepcopy(Item))

    def get_item(self, **kwargs):
        self.reads.append(kwargs)
        return {"Item": copy.deepcopy(self.items.get(kwargs["Key"]["versionKey"], {}))}


def load_worker(table, ecs, secrets):
    # Extract the deployment source, rather than maintaining a second handler.
    marker = "        ZipFile: |\n"
    block = TEMPLATE.split(marker, 1)[1].split("\n  WorkerEventSource:", 1)[0]
    source = textwrap.dedent(block)
    boto3 = types.ModuleType("boto3")
    boto3.client = Mock(side_effect=lambda service, **_: {"ecs": ecs, "secretsmanager": secrets}[service])
    boto3.resource = Mock(return_value=types.SimpleNamespace(Table=lambda _: table))
    conditions = types.ModuleType("boto3.dynamodb.conditions")
    conditions.Attr = Attr
    config = types.ModuleType("botocore.config")
    config.Config = Mock(return_value=object())
    modules = {
        "boto3": boto3,
        "boto3.dynamodb": types.ModuleType("boto3.dynamodb"),
        "boto3.dynamodb.conditions": conditions,
        "botocore": types.ModuleType("botocore"),
        "botocore.config": config,
    }
    env = {
        "LEASE_TABLE": "leases",
        "SECRET_ARN": SECRET_ARN,
        "CLUSTER_NAME": "test",
        "SERVICE_ARN": SERVICE_ARN,
    }
    worker = types.ModuleType("rotation_worker")
    with patch.dict(sys.modules, modules), patch.dict(os.environ, env):
        exec(compile(source, str(TEMPLATE_PATH) + ":ZipFile", "exec"), worker.__dict__)
    return worker, env


class RotationWorkerTests(unittest.TestCase):
    def setUp(self):
        self.table = FakeTable()
        self.ecs = Mock()
        self.secrets = Mock()
        self.secrets.describe_secret.return_value = {
            "VersionIdsToStages": {"version-1": ["AWSCURRENT"]}
        }
        self.worker, self.env = load_worker(self.table, self.ecs, self.secrets)
        self.env_patch = patch.dict(os.environ, self.env)
        self.env_patch.start()
        self.addCleanup(self.env_patch.stop)
        self.clock_patch = patch.object(self.worker.time, "time", return_value=NOW)
        self.clock_patch.start()
        self.addCleanup(self.clock_patch.stop)
        self.message = {
            "id": "label-move-1",
            "source": "aws.secretsmanager",
            "detail-type": "Secret Label Updated",
            "resources": [SECRET_ARN],
            "detail": {
                "name": "test",
                "labelUpdated": "AWSCURRENT",
                "versionId": "version-1",
            },
        }

    def invoke(self, message=None):
        return self.worker.handler(
            {"Records": [{"body": json.dumps(self.message if message is None else message)}]},
            None,
        )

    def seed(self, state, **kwargs):
        self.table.item = dict(
            versionKey=SECRET_ARN + "#version-1#" + self.message["id"],
            state=state,
            expiresAt=NOW + 30 * 86400,
            **kwargs,
        )

    def test_success_marks_completed_only_after_ecs_accepts(self):
        def accept(**kwargs):
            self.assertEqual(self.table.item["state"], "IN_PROGRESS")
            self.assertEqual(self.table.item["leaseUntil"], NOW + 120)

        self.ecs.update_service.side_effect = accept
        self.invoke()
        self.ecs.update_service.assert_called_once_with(
            cluster="test", service=SERVICE_ARN, forceNewDeployment=True
        )
        self.secrets.describe_secret.assert_called_once_with(SecretId=SECRET_ARN)
        self.assertEqual([w["state"] for w in self.table.writes], ["IN_PROGRESS", "COMPLETED"])
        self.assertEqual(self.table.item["expiresAt"], NOW + 30 * 86400)
        self.assertNotIn("owner", self.table.item)
        self.assertNotIn("get_secret_value", [call[0] for call in self.secrets.mock_calls])

    def test_completed_duplicate_does_not_deploy(self):
        self.seed("COMPLETED")
        with self.assertLogs("rotation_worker", level="INFO") as logs:
            self.invoke()
        self.ecs.update_service.assert_not_called()
        self.assertEqual(self.table.writes, [])
        self.assertTrue(self.table.reads[0]["ConsistentRead"])
        self.assertIn("completed duplicate", logs.output[0])

    def test_current_version_repromotion_is_distinct_from_delivery_retry(self):
        original = copy.deepcopy(self.message)
        self.invoke()
        self.message["id"] = "later-label-move"
        self.invoke()
        self.assertEqual(self.ecs.update_service.call_count, 2)
        self.assertEqual(len(self.table.items), 2)
        self.invoke(original)
        self.invoke()
        self.assertEqual(self.ecs.update_service.call_count, 2)

    def test_active_lease_raises_for_queue_retry(self):
        self.seed("IN_PROGRESS", owner="other", leaseUntil=NOW + 1)
        with self.assertRaisesRegex(RuntimeError, "lease is active"):
            self.invoke()
        self.ecs.update_service.assert_not_called()
        self.assertEqual(self.table.writes, [])

    def test_expired_lease_can_be_reclaimed(self):
        self.seed("IN_PROGRESS", owner="old", leaseUntil=NOW - 1)
        self.invoke()
        self.assertNotEqual(self.table.writes[0]["owner"], "old")
        self.ecs.update_service.assert_called_once()
        self.assertEqual(self.table.item["state"], "COMPLETED")

    def test_lease_expires_at_boundary(self):
        self.seed("IN_PROGRESS", owner="old", leaseUntil=NOW)
        self.invoke()
        self.ecs.update_service.assert_called_once()

    def test_ecs_failure_propagates_without_marking_completed(self):
        self.ecs.update_service.side_effect = RuntimeError("deployment in progress")
        with self.assertRaisesRegex(RuntimeError, "deployment in progress"):
            self.invoke()
        self.assertEqual(self.table.item["state"], "IN_PROGRESS")
        self.assertEqual(len(self.table.writes), 1)
        self.ecs.update_service.side_effect = None
        with patch.object(self.worker.time, "time", return_value=NOW + 600):
            self.invoke()
        self.assertEqual(self.ecs.update_service.call_count, 2)
        self.assertEqual(self.table.item["state"], "COMPLETED")

    def test_stale_version_is_logged_and_ignored(self):
        self.secrets.describe_secret.return_value = {
            "VersionIdsToStages": {
                "version-1": ["AWSPREVIOUS"],
                "version-2": ["AWSCURRENT"],
            }
        }
        with self.assertLogs("rotation_worker", level="INFO") as logs:
            self.invoke()
        self.assertIn("obsolete", logs.output[0])
        self.ecs.update_service.assert_not_called()
        self.assertEqual(self.table.writes, [])

    def test_missing_current_metadata_retries_instead_of_losing_event(self):
        self.secrets.describe_secret.return_value = {"VersionIdsToStages": {}}
        with self.assertRaisesRegex(RuntimeError, "no AWSCURRENT"):
            self.invoke()
        self.ecs.update_service.assert_not_called()
        self.assertEqual(self.table.writes, [])

    def test_eventual_metadata_visibility_retries_instead_of_skipping(self):
        for incoming_stages in (None, [], ["AWSPENDING"], ["custom-label"]):
            with self.subTest(incoming_stages=incoming_stages):
                versions = {"previous-current": ["AWSCURRENT"]}
                if incoming_stages is not None:
                    versions["version-1"] = incoming_stages
                self.secrets.describe_secret.return_value = {"VersionIdsToStages": versions}
                with self.assertRaisesRegex(RuntimeError, "not confirmed AWSCURRENT"):
                    self.invoke()
        self.ecs.update_service.assert_not_called()
        self.assertEqual(self.table.writes, [])
        self.secrets.describe_secret.return_value = {
            "VersionIdsToStages": {"version-1": ["AWSCURRENT"]}
        }
        self.invoke()
        self.ecs.update_service.assert_called_once()
        self.assertEqual(self.table.item["state"], "COMPLETED")

    def test_metadata_failure_propagates(self):
        self.secrets.describe_secret.side_effect = RuntimeError("access denied")
        with self.assertRaisesRegex(RuntimeError, "access denied"):
            self.invoke()
        self.assertEqual(self.table.writes, [])

    def test_irrelevant_events_raise_without_sdk_calls(self):
        mutations = [
            lambda m: m.update(source="aws.rds"),
            lambda m: m.update({"detail-type": "AWS API Call via CloudTrail"}),
            lambda m: m.update(resources=[SECRET_ARN + "-other"]),
            lambda m: m.update(resources=SECRET_ARN),
            lambda m: m["detail"].update(labelUpdated="AWSPREVIOUS"),
            lambda m: m.update(detail=[]),
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                message = copy.deepcopy(self.message)
                mutation(message)
                with self.assertRaisesRegex(ValueError, "Unexpected rotation event"):
                    self.invoke(message)
        self.secrets.describe_secret.assert_not_called()
        self.ecs.update_service.assert_not_called()

    def test_missing_or_invalid_detail_fields_raise(self):
        for field in ("name", "versionId"):
            for value in (None, "", " ", 123, []):
                with self.subTest(field=field, value=value):
                    message = copy.deepcopy(self.message)
                    message["detail"][field] = value
                    with self.assertRaisesRegex(ValueError, "requires versionId and name"):
                        self.invoke(message)
        self.secrets.describe_secret.assert_not_called()

    def test_missing_or_invalid_native_event_id_raises(self):
        for value in (None, "", " ", 123, []):
            with self.subTest(value=value):
                message = copy.deepcopy(self.message)
                message["id"] = value
                with self.assertRaisesRegex(ValueError, "native event id"):
                    self.invoke(message)
        self.secrets.describe_secret.assert_not_called()

    def test_malformed_queue_records_raise(self):
        for event in (None, {}, {"Records": []}, {"Records": [{}, {}]}, {"Records": "bad"}):
            with self.subTest(event=event), self.assertRaises(ValueError):
                self.worker.handler(event, None)
        with self.assertRaises(json.JSONDecodeError):
            self.worker.handler({"Records": [{"body": "not json"}]}, None)
        with self.assertRaisesRegex(ValueError, "rotation event object"):
            self.invoke([])

    def test_completion_write_failure_remains_retryable(self):
        real_write = self.table.put_item

        def fail_completion(**kwargs):
            if kwargs["Item"]["state"] == "COMPLETED":
                raise RuntimeError("DynamoDB unavailable after ECS update")
            return real_write(**kwargs)

        with patch.object(self.table, "put_item", side_effect=fail_completion):
            with self.assertRaisesRegex(RuntimeError, "DynamoDB unavailable"):
                self.invoke()
        self.assertEqual(self.table.item["state"], "IN_PROGRESS")
        with patch.object(self.worker.time, "time", return_value=NOW + 600):
            self.invoke()
        self.assertEqual(self.ecs.update_service.call_count, 2)
        self.assertEqual(self.table.item["state"], "COMPLETED")

    def test_dynamodb_errors_are_not_swallowed(self):
        with patch.object(self.table, "put_item", side_effect=RuntimeError("throttled")):
            with self.assertRaisesRegex(RuntimeError, "throttled"):
                self.invoke()
        self.ecs.update_service.assert_not_called()


class RotationTemplateTests(unittest.TestCase):
    def test_alarm_prefix_reuses_existing_monitoring_permissions(self):
        parameter = TEMPLATE.split("  AlarmNamePrefix:\n", 1)[1].split("Resources:", 1)[0]
        self.assertIn("Type: String", parameter)
        self.assertNotIn("Default:", parameter)
        for suffix in ("dead-letter", "backlog", "delivery-failures"):
            self.assertIn('AlarmName: !Sub "${AlarmNamePrefix}-' + suffix + '"', TEMPLATE)
        self.assertIn("alarm:${AlarmNamePrefix}-*", TEMPLATE)

    def test_native_event_pattern_and_no_secret_value_permissions(self):
        rule = TEMPLATE.split("  RotationRule:\n", 1)[1].split("  DeploymentLeases:", 1)[0]
        self.assertIn("EventBusName: default", rule)
        self.assertIn("detail-type: [Secret Label Updated]", rule)
        self.assertIn("resources:\n          - !Ref DatabaseMasterSecretArn", rule)
        self.assertIn("labelUpdated: [AWSCURRENT]", rule)
        pattern = rule.split("      EventPattern:\n", 1)[1].split("      Targets:", 1)[0]
        self.assertNotIn("requestParameters", pattern)
        self.assertNotIn("Action: secretsmanager:GetSecretValue", TEMPLATE)
        self.assertNotIn("get_secret_value(", TEMPLATE)

    def test_durable_encrypted_delivery_configuration(self):
        self.assertEqual(TEMPLATE.count("KmsMasterKeyId: !GetAtt QueueKey.Arn"), 2)
        self.assertIn("Resource: !GetAtt QueueKey.Arn", TEMPLATE)
        self.assertIn("Action: kms:Decrypt", TEMPLATE)
        self.assertNotIn("SqsManagedSseEnabled: true", TEMPLATE)
        for expected in (
            "VisibilityTimeout: 600",
            "MessageRetentionPeriod: 172800",
            "MessageRetentionPeriod: 1209600",
            "maxReceiveCount: 144",
            "MaximumEventAgeInSeconds: 86400",
            "MaximumRetryAttempts: 185",
            "Runtime: python3.13",
            "Timeout: 60",
            "BatchSize: 1",
            "AttributeName: expiresAt",
            "ConsistentRead=True",
        ):
            self.assertIn(expected, TEMPLATE)
        self.assertEqual(TEMPLATE.count("AlarmActions: [!Ref AlertTopicArn]"), 3)


if __name__ == "__main__":
    unittest.main()
