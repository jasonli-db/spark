# -*- coding: utf-8 -*-
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
import unittest
import numpy as np

from pyspark.ml.connect.evaluation import (
    RegressionEvaluator,
    BinaryClassificationEvaluator,
    MulticlassClassificationEvaluator,
)
from pyspark.sql import SparkSession


have_torcheval = True
try:
    import torcheval  # noqa: F401
except ImportError:
    have_torcheval = False


class EvaluationTestsMixin:
    def test_regressor_evaluator(self):
        df1 = self.spark.createDataFrame(
            [
                (0.5, 1.0),
                (-0.5, -0.8),
                (2.0, 3.0),
            ],
            schema=["label", "prediction"],
        )

        local_df1 = df1.toPandas()

        mse_evaluator = RegressionEvaluator(
            metricName="mse",
            labelCol="label",
            predictionCol="prediction",
        )

        expected_mse = 0.4466666877269745
        mse = mse_evaluator.evaluate(df1)
        mse_local = mse_evaluator.evaluate(local_df1)
        np.testing.assert_almost_equal(mse, expected_mse)
        np.testing.assert_almost_equal(mse_local, expected_mse)

        r2_evaluator = RegressionEvaluator(
            metricName="r2",
            labelCol="label",
            predictionCol="prediction",
        )

        expected_r2 = 0.5768420696258545
        r2 = r2_evaluator.evaluate(df1)
        r2_local = r2_evaluator.evaluate(local_df1)
        np.testing.assert_almost_equal(r2, expected_r2)
        np.testing.assert_almost_equal(r2_local, expected_r2)

    def test_binary_classifier_evaluator(self):
        df1 = self.spark.createDataFrame(
            [
                (1, 0.2, [0.8, 0.2]),
                (0, 0.6, [0.4, 0.6]),
                (1, 0.8, [0.2, 0.8]),
                (1, 0.7, [0.3, 0.7]),
                (0, 0.4, [0.6, 0.4]),
                (0, 0.3, [0.7, 0.3]),
            ],
            schema=["label", "prob", "prob2"],
        )

        local_df1 = df1.toPandas()

        for prob_col in ["prob", "prob2"]:
            auroc_evaluator = BinaryClassificationEvaluator(
                metricName="areaUnderROC",
                labelCol="label",
                probabilityCol=prob_col,
            )

            expected_auroc = 0.6667
            auroc = auroc_evaluator.evaluate(df1)
            auroc_local = auroc_evaluator.evaluate(local_df1)
            np.testing.assert_almost_equal(auroc, expected_auroc, decimal=2)
            np.testing.assert_almost_equal(auroc_local, expected_auroc, decimal=2)

            auprc_evaluator = BinaryClassificationEvaluator(
                metricName="areaUnderPR",
                labelCol="label",
                probabilityCol=prob_col,
            )

            expected_auprc = 0.8333
            auprc = auprc_evaluator.evaluate(df1)
            auprc_local = auprc_evaluator.evaluate(local_df1)
            np.testing.assert_almost_equal(auprc, expected_auprc, decimal=2)
            np.testing.assert_almost_equal(auprc_local, expected_auprc, decimal=2)

    def test_multiclass_classifier_evaluator(self):
        df1 = self.spark.createDataFrame(
            [
                (1, 1),
                (1, 1),
                (2, 3),
                (0, 0),
                (0, 1),
                (3, 1),
                (3, 3),
                (2, 2),
                (1, 0),
                (2, 2),
            ],
            schema=["label", "prediction"],
        )

        local_df1 = df1.toPandas()

        accuracy_evaluator = MulticlassClassificationEvaluator(
            metricName="accuracy",
            labelCol="label",
            predictionCol="prediction",
        )

        expected_accuracy = 0.600
        accuracy = accuracy_evaluator.evaluate(df1)
        accuracy_local = accuracy_evaluator.evaluate(local_df1)
        np.testing.assert_almost_equal(accuracy, expected_accuracy, decimal=2)
        np.testing.assert_almost_equal(accuracy_local, expected_accuracy, decimal=2)


@unittest.skipIf(not have_torcheval, "torcheval is required")
class EvaluationTests(EvaluationTestsMixin, unittest.TestCase):
    def setUp(self) -> None:
        self.spark = SparkSession.builder.master("local[2]").getOrCreate()

    def tearDown(self) -> None:
        self.spark.stop()


if __name__ == "__main__":
    from pyspark.ml.tests.connect.test_legacy_mode_evaluation import *  # noqa: F401,F403

    try:
        import xmlrunner  # type: ignore[import]

        testRunner = xmlrunner.XMLTestRunner(output="target/test-reports", verbosity=2)
    except ImportError:
        testRunner = None
    unittest.main(testRunner=testRunner, verbosity=2)
