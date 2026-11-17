"""Optional YAML-aware checks using PyYAML already present in the validation image."""

import json
from pathlib import Path
import unittest

try:
    import yaml
except ModuleNotFoundError:
    yaml = None


INFRASTRUCTURE = Path(__file__).resolve().parents[1]


def load_template(name):
    class CloudFormationLoader(yaml.SafeLoader):
        pass

    def intrinsic(loader, tag, node):
        if isinstance(node, yaml.ScalarNode):
            value = loader.construct_scalar(node)
        elif isinstance(node, yaml.SequenceNode):
            value = loader.construct_sequence(node)
        else:
            value = loader.construct_mapping(node)
        return {"Ref" if tag == "Ref" else "Fn::" + tag: value}

    CloudFormationLoader.add_multi_constructor("!", intrinsic)
    return yaml.load(
        (INFRASTRUCTURE / name).read_text(encoding="utf-8"),
        Loader=CloudFormationLoader,
    )


def secret_read_statements(role):
    statements = []
    for policy in role["Properties"].get("Policies", []):
        for statement in policy["PolicyDocument"]["Statement"]:
            actions = statement["Action"]
            if isinstance(actions, str):
                actions = [actions]
            if statement["Effect"] == "Allow" and any(
                action in ("secretsmanager:GetSecretValue", "secretsmanager:*", "*")
                for action in actions
            ):
                statements.append(statement)
    return statements


@unittest.skipUnless(yaml, "Optional PyYAML is available in the existing LocalStack venv")
class RotationIntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ecs = load_template("ecs-service.yaml")
        cls.rotation = load_template("database-rotation.yaml")

    def test_task_injects_current_secret_json_keys(self):
        containers = self.ecs["Resources"]["TaskDefinition"]["Properties"]["ContainerDefinitions"]
        references = {
            secret["Name"]: secret["ValueFrom"]
            for container in containers
            for secret in container.get("Secrets", [])
        }
        self.assertEqual(
            references["DATABASE_USERNAME"],
            {"Fn::Sub": "${DatabaseMasterSecretArn}:username::"},
        )
        self.assertEqual(
            references["DATABASE_PASSWORD"],
            {"Fn::Sub": "${DatabaseMasterSecretArn}:password::"},
        )
        for container in containers:
            names = {entry["Name"] for entry in container.get("Environment", [])}
            self.assertTrue(names.isdisjoint({"DATABASE_USERNAME", "DATABASE_PASSWORD"}))

    def test_execution_role_secret_access_is_exactly_scoped(self):
        statements = secret_read_statements(self.ecs["Resources"]["TaskExecutionRole"])
        self.assertTrue(statements, "Fargate must be able to inject the managed secret")
        for statement in statements:
            self.assertEqual(statement["Action"], "secretsmanager:GetSecretValue")
            self.assertEqual(statement["Resource"], {"Ref": "DatabaseMasterSecretArn"})

    def test_application_role_cannot_read_database_managed_secret(self):
        role = self.ecs["Resources"]["TaskRole"]
        self.assertNotIn("DatabaseMasterSecretArn", json.dumps(role["Properties"]))
        for statement in secret_read_statements(role):
            self.assertEqual(statement["Action"], "secretsmanager:GetSecretValue")
            self.assertEqual(statement["Resource"], {"Ref": "ApplicationSecretArn"})

    def test_deployment_does_not_copy_database_credentials(self):
        script = (INFRASTRUCTURE / "deploy-blue-green.ps1").read_text(encoding="utf-8")
        self.assertNotRegex(script, r"(?i)\$databaseCredentials\b")
        self.assertNotIn("DATABASE_USERNAME", script)
        self.assertNotIn("DATABASE_PASSWORD", script)
        self.assertIn("DatabaseMasterSecretArn=$databaseMasterSecretArn", script)

    def test_rotation_targets_the_injected_secret_and_preserves_failures(self):
        resources = self.rotation["Resources"]
        pattern = resources["RotationRule"]["Properties"]["EventPattern"]
        self.assertEqual(pattern["resources"], [{"Ref": "DatabaseMasterSecretArn"}])
        self.assertEqual(pattern["detail"], {"labelUpdated": ["AWSCURRENT"]})
        worker_environment = resources["RotationWorker"]["Properties"]["Environment"]["Variables"]
        self.assertEqual(worker_environment["SECRET_ARN"], {"Ref": "DatabaseMasterSecretArn"})
        for name in ("WorkQueue", "DeadLetterQueue", "QueueKey", "DeploymentLeases"):
            self.assertEqual(resources[name]["DeletionPolicy"], "Retain")
            self.assertEqual(resources[name]["UpdateReplacePolicy"], "Retain")
        for name in ("WorkQueue", "DeadLetterQueue"):
            self.assertEqual(
                resources[name]["Properties"]["KmsMasterKeyId"],
                {"Fn::GetAtt": "QueueKey.Arn"},
            )
        self.assertEqual(
            resources["WorkQueue"]["Properties"]["RedrivePolicy"]["deadLetterTargetArn"],
            {"Fn::GetAtt": "DeadLetterQueue.Arn"},
        )


if __name__ == "__main__":
    unittest.main()
