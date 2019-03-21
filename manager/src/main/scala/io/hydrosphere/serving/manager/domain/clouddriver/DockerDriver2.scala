package io.hydrosphere.serving.manager.domain.clouddriver

import cats._
import cats.implicits._
import com.spotify.docker.client.DockerClient
import com.spotify.docker.client.DockerClient.{ListContainersParam, RemoveContainerParam}
import com.spotify.docker.client.messages._
import io.hydrosphere.serving.manager.config.CloudDriverConfiguration
import io.hydrosphere.serving.manager.domain.image.DockerImage
import io.hydrosphere.serving.manager.domain.servable.{Servable, ServableStatus}

import scala.collection.JavaConverters._
import scala.util.Try

class DockerDriver2[F[_]](
  client: DockerdClient[F],
  config: CloudDriverConfiguration.Docker)(
  implicit F: MonadError[F, Throwable]
) extends CloudDriver2[F] {
  
  import DockerDriver2._
  
  override def instances: F[List[Servable]] = {
    client.listContainers.map(all => {
      all.map(containerToInstance).collect({ case Some(v) => v })
    })
  }
  
  private def containerOf(name: String, id: String): F[Option[Container]] = {
    val query = List(
      ListContainersParam.withLabel(Labels.ServiceName, name),
      ListContainersParam.withLabel(Labels.ServiceId, id)
    )
    client.listContainers(query).map(_.headOption)
  }
  
  override def instance(name: String, id: String): F[Option[Servable]] =
    containerOf(name, id).map(_.flatMap(containerToInstance))
  
  override def run(
    id: Long,
    name: String,
    modelVersionId: Long,
    image: DockerImage
  ): F[Servable] = {
    val container = Internals.mkContainerConfig(id, name, modelVersionId, image, config)
    for {
      creation <- client.createContainer(container, None)
      _        <- client.runContainer(creation.id())
      maybeOut <- instance(name, id.toString)
      out      <- maybeOut match {
        case Some(v) => F.pure(v)
        case None =>
          val warnings = Option(creation.warnings()) match {
            case Some(l) => l.asScala.mkString("\n")
            case None => ""
          }
          val msg = s"Running docker container for ${id} failed. Warnings: \n $warnings"
          F.raiseError(new RuntimeException(msg))
      }
    } yield out
  }
  
  override def remove(name: String, id: String): F[Unit] = {
    for {
      maybeC  <- containerOf(name, id)
      _       <- maybeC match {
        case Some(c) =>
          val params = List(
            RemoveContainerParam.forceKill(true),
            RemoveContainerParam.removeVolumes(true),
          )
          client.removeContainer(c.id, params)
        case None => F.raiseError(new Exception(s"Could not find container for $name $id"))
      }
    } yield ()
  }
  
  private def containerToInstance(c: Container): Option[Servable] = {
    val labels = c.labels().asScala
  
    val mId = labels.get(Labels.ServiceId).flatMap(i => Try(i.toLong).toOption)
    val mName = labels.get(Labels.ServiceName)
    val mMvId = labels.get(Labels.ModelVersionId).flatMap(i => Try(i.toLong).toOption)
  
    (mId, mName, mMvId).mapN((id, name, mvId) => {
      val host = Internals.extractIpAddress(c.networkSettings(), config.networkName)
      val status = ServableStatus.Running(host, DefaultConstants.DEFAULT_APP_PORT)
      Servable(id, mvId, name, status)
    })
  }
  
  
}

object DockerDriver2 {
  
  object Labels {
    val ServiceName = "HS_INSTANCE_NAME"
    val ModelVersionId = "HS_INSTANCE_MV_ID"
    val ServiceId = "HS_INSTANCE_ID"
  }
  
  object Internals {
  
    def mkContainerConfig(
      id: Long,
      name: String,
      modelVersionId: Long,
      image: DockerImage,
      dockerConf: CloudDriverConfiguration.Docker
    ): ContainerConfig = {
      val hostConfig = {
        val builder = HostConfig.builder().networkMode(dockerConf.networkName)
        val withLogs = dockerConf.loggingConfiguration match {
          case Some(c) => builder.logConfig(LogConfig.create(c.driver, c.params.asJava))
          case None => builder
        }
        withLogs.build()
      }
  
      val labels = Map(
        Labels.ServiceName -> name,
        Labels.ServiceId -> id.toString,
        Labels.ModelVersionId -> modelVersionId.toString
      )
      val envMap = Map(
        DefaultConstants.ENV_MODEL_DIR -> DefaultConstants.DEFAULT_MODEL_DIR.toString,
        DefaultConstants.ENV_APP_PORT -> DefaultConstants.DEFAULT_APP_PORT.toString,
        DefaultConstants.LABEL_SERVICE_ID -> id.toString
      )
  
      val envs = envMap.map({ case (k, v) => s"$k=$v"}).toList.asJava
  
      ContainerConfig.builder()
        .image(image.fullName)
        .exposedPorts(DefaultConstants.DEFAULT_APP_PORT.toString)
        .labels(labels.asJava)
        .hostConfig(hostConfig)
        .env(envs)
        .build()
    }
  
    def extractIpAddress(settings: NetworkSettings, networkName: String): String = {
      val byNetworkName = Option(settings.networks().get(networkName)).map(_.ipAddress())
      byNetworkName.getOrElse(settings.ipAddress())
    }
  }
  
  
}