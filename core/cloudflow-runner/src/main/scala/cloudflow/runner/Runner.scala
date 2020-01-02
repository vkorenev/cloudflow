/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cloudflow.runner

import scala.util.{ Failure, Success, Try }
import scala.concurrent.Await
import scala.concurrent.duration._
import java.nio.file.{ Files, Paths }

import org.slf4j.LoggerFactory
import com.typesafe.config.Config
import cloudflow.streamlets._
import RunnerOps._
import cloudflow.events.errors.ErrorEvents

/**
 * Runner for cluster deployments. Assumes Linux-style paths!
 */
object Runner extends RunnerConfigResolver with StreamletLoader {
  lazy val log = LoggerFactory.getLogger(getClass.getName)

  sys.props.get("os.name") match {
    case Some(os) if os.startsWith("Win") ⇒
      log.error("cloudflow.runner.Runner is NOT compatible with Windows!!")
    case None ⇒ log.warn("""sys.props.get("os.name") returned None!""")
    case _    ⇒ // okay
  }

  val PVCMountPath: String = "/mnt/spark/storage"
  val DownwardApiVolumeMountPath: String = "/mnt/downward-api-volume"

  def main(args: Array[String]): Unit = run()

  private def run(): Unit = {

    val result: Try[(Config, LoadedStreamlet)] = for {
      runnerConfig ← makeConfig
      loadedStreamlet ← loadStreamlet(runnerConfig)
    } yield (runnerConfig, loadedStreamlet)

    result match {
      case Success((runnerConfig, loadedStreamlet)) ⇒
        val withStorageConfig = addStorageConfig(runnerConfig, PVCMountPath)
        val withPodRuntimeConfig = addPodRuntimeConfig(withStorageConfig, DownwardApiVolumeMountPath)

        /*
         * The following call to `run` must not be in the `Try` block. As part of job planning
         * and execution, Flink uses `OptimizerPlanEnvironment.ProgramAbortException` for control flow.
         * If we execute `run` within a `Try` block then this exception gets caught and the environment
         * in Flink somehow gets messed up.
         *
         * Need to learn more on what exactly happens here with Flink.
         */
        val streamletExecution = loadedStreamlet.streamlet.run(withPodRuntimeConfig)
        loadedStreamlet.streamlet.logStartRunnerMessage(formatBuildInfo)

        Try {
          // the runner waits for the execution to complete
          // In normal circumstances it will run forever for streaming data source unless
          // being stopped forcibly or any of the queries faces an exception
          Await.result(streamletExecution.completed, Duration.Inf)
        } match {
          case Success(_) ⇒ System.exit(0)
          case Failure(ex @ ExceptionAcc(exceptions)) ⇒
            exceptions.foreach(ErrorEvents.report(loadedStreamlet, withPodRuntimeConfig, _))
            shutdownWithFailure(loadedStreamlet, ex)
          case Failure(ex) ⇒
            ErrorEvents.report(loadedStreamlet, withPodRuntimeConfig, ex)
            shutdownWithFailure(loadedStreamlet, ex)
        }
      case Failure(ex) ⇒ throw new Exception(ex)
    }
  }

  private def shutdownWithFailure(loadedStreamlet: LoadedStreamlet, ex: Throwable) = {
    // we created this file when the pod started running (see AkkaStreamlet#run)
    Files.deleteIfExists(
      Paths.get(s"/tmp/${loadedStreamlet.config.streamletRef}.txt")
    )
    log.error("Fatal error has occurred:", ex)

    System.exit(-1)
  }

  private def formatBuildInfo: String = {
    import BuildInfo._

    s"""
    |Name          : $name
    |Version       : $version
    |Scala Version : $scalaVersion
    |sbt Version   : $sbtVersion
    |Build Time    : $buildTime
    |Build User    : $buildUser
    """.stripMargin
  }

}
