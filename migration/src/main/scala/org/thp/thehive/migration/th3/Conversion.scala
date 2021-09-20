package org.thp.thehive.migration.th3

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.thp.scalligraph.utils.Hash
import org.thp.thehive.connector.cortex.models.{Action, Job, JobStatus}
import org.thp.thehive.controllers.v0
import org.thp.thehive.dto.v1.InputCustomFieldValue
import org.thp.thehive.migration.dto._
import org.thp.thehive.models._
import play.api.libs.functional.syntax._
import play.api.libs.json.JsValue.jsValueToJsLookup
import play.api.libs.json._
import java.util.{Base64, Date}
import scala.collection.mutable

case class Attachment(name: String, hashes: Seq[Hash], size: Long, contentType: String, id: String)
trait Conversion {

  private val attachmentWrites: OWrites[Attachment] = OWrites[Attachment] { attachment =>
    Json.obj(
      "name"        -> attachment.name,
      "hashes"      -> attachment.hashes,
      "size"        -> attachment.size,
      "contentType" -> attachment.contentType,
      "id"          -> attachment.id
    )
  }

  private val attachmentReads: Reads[Attachment] = Reads { json =>
    for {
      name        <- (json \ "name").validate[String]
      hashes      <- (json \ "hashes").validate[Seq[Hash]]
      size        <- (json \ "size").validate[Long]
      contentType <- (json \ "contentType").validate[String]
      id          <- (json \ "id").validate[String]
    } yield Attachment(name, hashes, size, contentType, id)
  }
  implicit val attachmentFormat: OFormat[Attachment] = OFormat(attachmentReads, attachmentWrites)

  def readAttachment(id: String): Source[ByteString, NotUsed]
  val mainOrganisation: String

  implicit val metaDataReads: Reads[MetaData] =
    ((JsPath \ "_id").read[String] and
      (JsPath \ "createdBy").readWithDefault[String]("system@thehive.local").map(normaliseLogin) and
      (JsPath \ "createdAt").readWithDefault[Date](new Date) and
      (JsPath \ "updatedBy").readNullable[String].map(_.map(normaliseLogin)) and
      (JsPath \ "updatedAt").readNullable[Date])(MetaData.apply _)

  implicit val caseReads: Reads[InputCase] = Reads[InputCase] { json =>
    for {
      metaData    <- json.validate[MetaData]
      number      <- (json \ "caseId").validate[Int]
      title       <- (json \ "title").validate[String]
      description <- (json \ "description").validate[String]
      severity    <- (json \ "severity").validate[Int]
      startDate   <- (json \ "startDate").validate[Date]
      endDate     <- (json \ "endDate").validateOpt[Date]
      flag        <- (json \ "flag").validate[Boolean]
      tlp         <- (json \ "tlp").validate[Int]
      pap         <- (json \ "pap").validate[Int]
      status      <- (json \ "status").validate[CaseStatus.Value]
      summary     <- (json \ "summary").validateOpt[String]
      user        <- (json \ "owner").validateOpt[String]
      tags             = (json \ "tags").asOpt[Set[String]].getOrElse(Set.empty).filterNot(_.isEmpty)
      metrics          = (json \ "metrics").asOpt[JsObject].getOrElse(JsObject.empty)
      resolutionStatus = (json \ "resolutionStatus").asOpt[String]
      impactStatus     = (json \ "impactStatus").asOpt[String]
      metricsValue = metrics.value.map {
        case (name, value) => name -> Some(value)
      }
      customFields = (json \ "customFields").asOpt[JsObject].getOrElse(JsObject.empty)
      customFieldsValue = customFields.value.map {
        case (name, value) =>
          name -> Some((value \ "string") orElse (value \ "boolean") orElse (value \ "number") orElse (value \ "date") getOrElse JsNull)
      }
    } yield InputCase(
      Case(
        title = title,
        description = description,
        severity = severity,
        startDate = startDate,
        endDate = endDate,
        flag = flag,
        tlp = tlp,
        pap = pap,
        status = status,
        summary = summary,
        tags = tags.toSeq,
        number = number,
        organisationIds = Set.empty,
        assignee = user.map(normaliseLogin),
        impactStatus = impactStatus,
        resolutionStatus = resolutionStatus,
        caseTemplate = None
      ), // organisation Ids are filled by output
      Map(mainOrganisation -> Profile.orgAdmin.name),
      (metricsValue ++ customFieldsValue).toMap,
      metaData
    )
  }

  def getTaxonomies(input: Map[String, JsValue]): List[ReportTag] = {
    val taxonomies = mutable.MutableList[ReportTag]()
    input.foreach{ case (origin: String, value: JsValue) =>
      (jsValueToJsLookup(value) \ "taxonomies").asOpt[List[Map[String, JsValue]]].getOrElse(List.empty).foreach(taxonomy => {
        taxonomies += ReportTag(
          origin,
          taxonomy.get("level") match {
            case Some(x) => x.asOpt[String].getOrElse("") match {
              case "malicious" => ReportTagLevel.malicious
              case "suspicious" => ReportTagLevel.suspicious
              case "safe" => ReportTagLevel.safe
              case "info" => ReportTagLevel.info
            }
            case None    => throw new Exception
          },
          taxonomy.get("namespace") match {
            case Some(x) => x.asOpt[String].getOrElse("")
            case None    => throw new Exception
          },
          taxonomy.get("predicate") match {
            case Some(x) => x.asOpt[String].getOrElse("")
            case None    => throw new Exception
          },
          taxonomy.get("value") match {
            case Some(x) => x
            case None    => throw new Exception
          }
        )
      })
    }

    return taxonomies.toList
  }

  implicit val observableReads: Reads[InputObservable] = Reads[InputObservable] { json =>
    for {
      metaData <- json.validate[MetaData]
      message  <- (json \ "message").validateOpt[String]
      tlp      <- (json \ "tlp").validate[Int]
      ioc      <- (json \ "ioc").validate[Boolean]
      sighted  <- (json \ "sighted").validate[Boolean]
      dataType <- (json \ "dataType").validate[String]

      tags = (json \ "tags").asOpt[Set[String]].getOrElse(Set.empty)
      taxonomiesList = getTaxonomies(Json.parse((json \ "reports").asOpt[String].getOrElse("{}")).as[Map[String, JsValue]])
      dataOrAttachment <-
        (json \ "data")
          .validate[String]
          .map(Left.apply)
          .orElse(
            (json \ "attachment")
              .validate[Attachment]
              .map(a => Right(InputAttachment(a.name, a.size, a.contentType, a.hashes.map(_.toString), readAttachment(a.id))))
          )
    } yield InputObservable(
      metaData,
      Observable(
        message = message,
        tlp = tlp,
        ioc = ioc,
        sighted = sighted,
        ignoreSimilarity = None,
        dataType = dataType,
        tags = tags.toSeq
      ),
      Set(mainOrganisation),
      dataOrAttachment,
      taxonomiesList
    )
  }

  implicit val taskReads: Reads[InputTask] = Reads[InputTask] { json =>
    for {
      metaData    <- json.validate[MetaData]
      title       <- (json \ "title").validate[String]
      group       <- (json \ "group").validate[String]
      description <- (json \ "description").validateOpt[String]
      status      <- (json \ "status").validate[TaskStatus.Value]
      flag        <- (json \ "flag").validate[Boolean]
      startDate   <- (json \ "startDate").validateOpt[Date]
      endDate     <- (json \ "endDate").validateOpt[Date]
      order       <- (json \ "order").validate[Int]
      dueDate     <- (json \ "dueDate").validateOpt[Date]
      owner       <- (json \ "owner").validateOpt[String]
    } yield InputTask(
      metaData,
      Task(
        title = title,
        group = group,
        description = description,
        status = status,
        flag = flag,
        startDate = startDate,
        endDate = endDate,
        order = order,
        dueDate = dueDate,
        assignee = owner.map(normaliseLogin)
      ),
      owner.map(normaliseLogin),
      Set(mainOrganisation)
    )
  }

  implicit val logReads: Reads[InputLog] = Reads[InputLog] { json =>
    for {
      metaData <- json.validate[MetaData]
      message  <- (json \ "message").validate[String]
      date     <- (json \ "startDate").validate[Date]
      attachment =
        (json \ "attachment")
          .asOpt[Attachment]
          .map(a => InputAttachment(a.name, a.size, a.contentType, a.hashes.map(_.toString), readAttachment(a.id)))
    } yield InputLog(metaData, Log(message, date), attachment.toSeq)
  }

  implicit val alertReads: Reads[InputAlert] = Reads[InputAlert] { json =>
    for {
      metaData     <- json.validate[MetaData]
      tpe          <- (json \ "type").validate[String]
      source       <- (json \ "source").validate[String]
      sourceRef    <- (json \ "sourceRef").validate[String]
      externalLink <- (json \ "externalLink").validateOpt[String]
      title        <- (json \ "title").validate[String]
      description  <- (json \ "description").validate[String]
      severity     <- (json \ "severity").validate[Int]
      date         <- (json \ "date").validate[Date]
      lastSyncDate <- (json \ "lastSyncDate").validate[Date]
      tlp          <- (json \ "tlp").validate[Int]
      pap          <- (json \ "pap").validateOpt[Int] // not in TH3
      status       <- (json \ "status").validate[String]
      read = status == "Ignored" || status == "Imported"
      follow <- (json \ "follow").validate[Boolean]
      caseId <- (json \ "case").validateOpt[String]
      tags         = (json \ "tags").asOpt[Set[String]].getOrElse(Set.empty).filterNot(_.isEmpty)
      customFields = (json \ "metrics").asOpt[JsObject].getOrElse(JsObject.empty)
      customFieldsValue = customFields.value.map {
        case (name, value) =>
          name -> Some((value \ "string") orElse (value \ "boolean") orElse (value \ "number") orElse (value \ "date") getOrElse JsNull)
      }
      caseTemplate <- (json \ "caseTemplate").validateOpt[String]
    } yield InputAlert(
      metaData: MetaData,
      Alert(
        `type` = tpe,
        source = source,
        sourceRef = sourceRef,
        externalLink = externalLink,
        title = title,
        description = description,
        severity = severity,
        date = date,
        lastSyncDate = lastSyncDate,
        tlp = tlp,
        pap = pap.getOrElse(2),
        read = read,
        follow = follow,
        tags = tags.toSeq
      ),
      caseId,
      mainOrganisation,
      customFieldsValue.toMap,
      caseTemplate: Option[String]
    )
  }

  def alertObservableReads(metaData: MetaData): Reads[InputObservable] =
    Reads[InputObservable] { json =>
      for {
        dataType <- (json \ "dataType").validate[String]
        message  <- (json \ "message").validateOpt[String]
        tlp      <- (json \ "tlp").validateOpt[Int]
        tags = (json \ "tags").asOpt[Set[String]].getOrElse(Set.empty)
        ioc <- (json \ "ioc").validateOpt[Boolean]
        dataOrAttachment <-
          (json \ "data")
            .validate[String]
            .map(Left.apply)
            .orElse(
              (json \ "attachment")
                .validate[Attachment]
                .map(a => Right(InputAttachment(a.name, a.size, a.contentType, a.hashes.map(_.toString), readAttachment(a.id))))
            )
      } yield InputObservable(
        metaData,
        Observable(
          message = message,
          tlp = tlp.getOrElse(2),
          ioc = ioc.getOrElse(false),
          sighted = false,
          ignoreSimilarity = None,
          dataType = dataType,
          tags = tags.toSeq
        ),
        Set(mainOrganisation),
        dataOrAttachment,
        List()
      )

    }

  def normaliseLogin(login: String): String = {
    def validSegment(value: String) = {
      var len   = value.length
      var start = 0
      while (start < len && (value(start) == '.' || value(start) == '-')) start += 1
      while (start < len && (value(len - 1) == '.' || value(len - 1) == '-')) len -= 1
      if (start == len) "empty.name" else value.substring(start, len)
    }
    (login.replaceAll("[^\\w@-]+", ".").replaceFirst("\\W*$", "").split('@') match {
      case Array(l)         => validSegment(l)
      case Array(l, d @ _*) => validSegment(l) + '@' + validSegment(d.mkString("."))
    }).toLowerCase
  }

  implicit val userReads: Reads[InputUser] = Reads[InputUser] { json =>
    for {
      metaData <- json.validate[MetaData]
      login    <- (json \ "_id").validate[String]
      name     <- (json \ "name").validate[String]
      apikey   <- (json \ "key").validateOpt[String]
      status   <- (json \ "status").validate[String]
      locked = status == "Locked"
      password <- (json \ "password").validateOpt[String]
      role     <- (json \ "roles").validateOpt[Seq[String]].map(_.getOrElse(Nil))
      organisationProfiles =
        if (role.contains("admin")) Map(mainOrganisation -> Profile.orgAdmin.name)
        else if (role.contains("write")) Map(mainOrganisation -> Profile.analyst.name)
        else if (role.contains("read")) Map(mainOrganisation -> Profile.readonly.name)
        else Map(mainOrganisation                            -> Profile.readonly.name)
      avatar =
        (json \ "avatar")
          .asOpt[String]
          .map { base64 =>
            val data = Base64.getDecoder.decode(base64)
            InputAttachment(s"$login.avatar", data.size.toLong, "image/png", Nil, Source.single(ByteString(data)))
          }
    } yield InputUser(metaData, User(normaliseLogin(login), name, apikey, locked, password, None), organisationProfiles, avatar)
  }

  val metricsReads: Reads[InputCustomField] = Reads[InputCustomField] { json =>
    for {
      valueJson <- (json \ "value").validate[String]
      value = Json.parse(valueJson)
      name <- (value \ "name").validate[String]
//      title       <- (value \ "title").validate[String]
      description <- (value \ "description").validate[String]
    } yield InputCustomField(
      MetaData(name, User.init.login, new Date, None, None),
      CustomField(name, name, description, CustomFieldType.integer, mandatory = true, Nil)
    )
  }

  implicit val customFieldReads: Reads[InputCustomField] = Reads[InputCustomField] { json =>
    for {
      //      metaData    <- json.validate[MetaData]
      valueJson <- (json \ "value").validate[String]
      value = Json.parse(valueJson)
      displayName <- (value \ "name").validate[String]
      name        <- (value \ "reference").validate[String]
      description <- (value \ "description").validate[String]
      tpe         <- (value \ "type").validate[String]
      customFieldType = tpe match {
        case "string"  => CustomFieldType.string
        case "number"  => CustomFieldType.integer
        case "integer" => CustomFieldType.integer
        case "float"   => CustomFieldType.float
        case "boolean" => CustomFieldType.boolean
        case "date"    => CustomFieldType.date
      }
      options = (value \ "options").asOpt[Seq[JsValue]].getOrElse(Nil)
    } yield InputCustomField(
      MetaData(name, User.init.login, new Date, None, None),
      CustomField(name, displayName, description, customFieldType, mandatory = false, options)
    )
  } orElse metricsReads

  implicit val observableTypeReads: Reads[InputObservableType] = Reads[InputObservableType] { json =>
    for {
      //      metaData    <- json.validate[MetaData]
      valueJson <- (json \ "value").validate[String]
      value = Json.parse(valueJson)
      name <- value.validate[String]
    } yield InputObservableType(MetaData(name, User.init.login, new Date, None, None), ObservableType(name, name == "file"))
  }

  implicit val caseTemplateReads: Reads[InputCaseTemplate] = Reads[InputCaseTemplate] { json =>
    for {
      metaData    <- json.validate[MetaData]
      name        <- (json \ "name").validate[String]
      displayName <- (json \ "name").validate[String]
      description <- (json \ "description").validateOpt[String]
      titlePrefix <- (json \ "titlePrefix").validateOpt[String]
      severity    <- (json \ "severity").validateOpt[Int]
      flag = (json \ "flag").asOpt[Boolean].getOrElse(false)
      tlp     <- (json \ "tlp").validateOpt[Int]
      pap     <- (json \ "pap").validateOpt[Int]
      summary <- (json \ "summary").validateOpt[String]
      tags    = (json \ "tags").asOpt[Set[String]].getOrElse(Set.empty)
      metrics = (json \ "metrics").asOpt[JsObject].getOrElse(JsObject.empty)
      metricsValue = metrics.value.map {
        case (name, value) => InputCustomFieldValue(name, Some(value), None)
      }
      customFields <- (json \ "customFields").validateOpt[JsObject]
      customFieldsValue = customFields.getOrElse(JsObject.empty).value.map {
        case (name, value) =>
          InputCustomFieldValue(
            name,
            Some((value \ "string") orElse (value \ "boolean") orElse (value \ "number") orElse (value \ "date") getOrElse JsNull),
            (value \ "order").asOpt[Int]
          )
      }
    } yield InputCaseTemplate(
      metaData,
      CaseTemplate(
        name = name,
        displayName = displayName,
        titlePrefix = titlePrefix,
        description = description,
        tags = tags.toSeq,
        severity = severity,
        flag = flag,
        tlp = tlp,
        pap = pap,
        summary = summary
      ),
      mainOrganisation,
      (metricsValue ++ customFieldsValue).toSeq
    )
  }

  def caseTemplateTaskReads(metaData: MetaData): Reads[InputTask] =
    Reads[InputTask] { json =>
      for {
        title       <- (json \ "title").validate[String]
        group       <- (json \ "group").validateOpt[String]
        description <- (json \ "description").validateOpt[String]
        status      <- (json \ "status").validateOpt[TaskStatus.Value]
        flag        <- (json \ "flag").validateOpt[Boolean]
        startDate   <- (json \ "startDate").validateOpt[Date]
        endDate     <- (json \ "endDate").validateOpt[Date]
        order       <- (json \ "order").validateOpt[Int]
        dueDate     <- (json \ "dueDate").validateOpt[Date]
        owner       <- (json \ "owner").validateOpt[String]
      } yield InputTask(
        metaData,
        Task(
          title = title,
          group = group.getOrElse("default"),
          description = description,
          status = status.getOrElse(TaskStatus.Waiting),
          flag = flag.getOrElse(false),
          startDate = startDate,
          endDate = endDate,
          order = order.getOrElse(1),
          dueDate = dueDate,
          assignee = owner.map(normaliseLogin)
        ),
        owner.map(normaliseLogin),
        Set(mainOrganisation)
      )
    }

  lazy val jobReads: Reads[InputJob] = Reads[InputJob] { json =>
    for {
      metaData         <- json.validate[MetaData]
      workerId         <- (json \ "analyzerId").validate[String]
      workerName       <- (json \ "analyzerName").validate[String]
      workerDefinition <- (json \ "analyzerDefinition").validate[String]
      status           <- (json \ "status").validate[JobStatus.Value]
      startDate        <- (json \ "createdAt").validate[Date]
      endDate          <- (json \ "endDate").validate[Date]
      reportJson       <- (json \ "report").validateOpt[String]
      report = reportJson.flatMap { j =>
        (Json.parse(j) \ "full").asOpt[JsObject]
      }
      cortexId    <- (json \ "cortexId").validate[String]
      cortexJobId <- (json \ "cortexJobId").validate[String]
    } yield InputJob(
      metaData,
      Job(
        workerId,
        workerName,
        workerDefinition,
        status,
        startDate,
        endDate,
        report,
        cortexId,
        cortexJobId
      )
    )
  }

  def jobObservableReads(metaData: MetaData): Reads[InputObservable] =
    Reads[InputObservable] { json =>
      for {
        message  <- (json \ "message").validateOpt[String] orElse (json \ "attributes" \ "message").validateOpt[String]
        tlp      <- (json \ "tlp").validate[Int] orElse (json \ "attributes" \ "tlp").validate[Int] orElse JsSuccess(2)
        ioc      <- (json \ "ioc").validate[Boolean] orElse (json \ "attributes" \ "ioc").validate[Boolean] orElse JsSuccess(false)
        sighted  <- (json \ "sighted").validate[Boolean] orElse (json \ "attributes" \ "sighted").validate[Boolean] orElse JsSuccess(false)
        dataType <- (json \ "dataType").validate[String] orElse (json \ "type").validate[String] orElse (json \ "attributes").validate[String]
        tags     <- (json \ "tags").validate[Set[String]] orElse (json \ "attributes" \ "tags").validate[Set[String]] orElse JsSuccess(Set.empty[String])
        dataOrAttachment <- ((json \ "data").validate[String] orElse (json \ "value").validate[String])
          .map(Left.apply)
          .orElse(
            (json \ "attachment")
              .validate[Attachment]
              .map(a => Right(InputAttachment(a.name, a.size, a.contentType, a.hashes.map(_.toString), readAttachment(a.id))))
          )
      } yield InputObservable(
        metaData,
        Observable(
          message = message,
          tlp = tlp,
          ioc = ioc,
          sighted = sighted,
          ignoreSimilarity = None,
          dataType = dataType,
          tags = tags.toSeq
        ),
        Set(mainOrganisation),
        dataOrAttachment,
        List()
      )
    }

  implicit val actionReads: Reads[(String, InputAction)] = Reads[(String, InputAction)] { json =>
    for {
      metaData         <- json.validate[MetaData]
      workerId         <- (json \ "responderId").validate[String]
      workerName       <- (json \ "responderName").validateOpt[String]
      workerDefinition <- (json \ "responderDefinition").validateOpt[String]
      status           <- (json \ "status").validate[JobStatus.Value]
      objectType       <- (json \ "objectType").validate[String]
      objectId         <- (json \ "objectId").validate[String]
      parameters = JsObject.empty // not in th3
      startDate   <- (json \ "startDate").validate[Date]
      endDate     <- (json \ "endDate").validateOpt[Date]
      report      <- (json \ "report").validateOpt[String]
      cortexId    <- (json \ "cortexId").validateOpt[String]
      cortexJobId <- (json \ "cortexJobId").validateOpt[String]
      operations  <- (json \ "operations").validateOpt[String]
    } yield objectId -> InputAction(
      metaData,
      v0.Conversion.toObjectType(objectType),
      Action(
        workerId,
        workerName.getOrElse(workerId),
        workerDefinition.getOrElse(workerId),
        status,
        parameters,
        startDate,
        endDate,
        report.flatMap(Json.parse(_).asOpt[JsObject]),
        cortexId.getOrElse("unknown"),
        cortexJobId.getOrElse("unknown"),
        operations.flatMap(Json.parse(_).asOpt[Seq[JsObject]]).getOrElse(Nil)
      )
    )
  }

  implicit val auditReads: Reads[(String, InputAudit)] = Reads[(String, InputAudit)] { json =>
    for {
      metaData   <- json.validate[MetaData]
      requestId  <- (json \ "requestId").validate[String]
      operation  <- (json \ "operation").validate[String]
      mainAction <- (json \ "base").validate[Boolean]
      objectId   <- (json \ "objectId").validateOpt[String]
      objectType <- (json \ "objectType").validateOpt[String]
      details    <- (json \ "details").validateOpt[JsObject]
      rootId     <- (json \ "rootId").validate[String]
    } yield (
      rootId,
      InputAudit(
        metaData,
        Audit(
          requestId,
          operation match {
            case "Update"   => "update"
            case "Creation" => "create"
            case "Delete"   => "delete"
          },
          mainAction,
          objectId,
          objectType.map(v0.Conversion.toObjectType),
          details.map(_.toString)
        )
      )
    )
  }
}