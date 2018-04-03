package com.prisma.api.connector.mysql.impl

import java.sql.{SQLException, SQLIntegrityConstraintViolationException}

import com.prisma.api.connector._
import com.prisma.api.connector.mysql.DatabaseMutactionInterpreter
import com.prisma.api.connector.mysql.database.DatabaseMutationBuilder.{
  cascadingDeleteChildActions,
  oldParentFailureTriggerByField,
  oldParentFailureTriggerByFieldAndFilter
}
import GetFieldFromSQLUniqueException.getFieldOption
import com.prisma.api.connector.mysql.database.{DatabaseMutationBuilder, ProjectRelayId, ProjectRelayIdTable}
import com.prisma.api.schema.APIErrors
import com.prisma.api.schema.APIErrors.RequiredRelationWouldBeViolated
import com.prisma.shared.models.{Field, Relation}
import com.prisma.util.gc_value.OtherGCStuff.parameterString
import slick.dbio.DBIOAction
import slick.jdbc.MySQLProfile.api._
import slick.lifted.TableQuery

case class AddDataItemToManyRelationByPathInterpreter(mutaction: AddDataItemToManyRelationByPath) extends DatabaseMutactionInterpreter {

  override val action = DatabaseMutationBuilder.createRelationRowByPath(mutaction.project.id, mutaction.path)
}

case class CascadingDeleteRelationMutactionsInterpreter(mutaction: CascadingDeleteRelationMutactions) extends DatabaseMutactionInterpreter {
  val path    = mutaction.path
  val project = mutaction.project

  val fieldsWhereThisModelIsRequired = project.schema.fieldsWhereThisModelIsRequired(path.lastModel)

  val otherFieldsWhereThisModelIsRequired = path.lastEdge match {
    case Some(edge) => fieldsWhereThisModelIsRequired.filter(f => f != edge.parentField)
    case None       => fieldsWhereThisModelIsRequired
  }

  override val action = {
    val requiredCheck = otherFieldsWhereThisModelIsRequired.map(oldParentFailureTriggerByField(project, path, _))
    val deleteAction  = List(cascadingDeleteChildActions(project.id, path))
    val allActions    = requiredCheck ++ deleteAction
    DBIOAction.seq(allActions: _*)
  }

  override def errorMapper = {
    case e: SQLException if e.getErrorCode == 1242 && otherFailingRequiredRelationOnChild(e.getCause.toString).isDefined =>
      throw RequiredRelationWouldBeViolated(project, otherFailingRequiredRelationOnChild(e.getCause.toString).get)
  }

  private def otherFailingRequiredRelationOnChild(cause: String): Option[Relation] =
    otherFieldsWhereThisModelIsRequired.collectFirst { case f if causedByThisMutactionChildOnly(f, cause) => f.relation.get }

  private def causedByThisMutactionChildOnly(field: Field, cause: String) = {
    val parentCheckString = s"`${field.relation.get.relationTableName}` OLDPARENTPATHFAILURETRIGGERBYFIELD WHERE `${field.oppositeRelationSide.get}`"

    path.lastEdge match {
      case Some(edge: NodeEdge) => cause.contains(parentCheckString) && cause.contains(parameterString(edge.childWhere))
      case _                    => cause.contains(parentCheckString)
    }
  }
}

case class CreateDataItemInterpreter(mutaction: CreateDataItem) extends DatabaseMutactionInterpreter {
  val project = mutaction.project
  val path    = mutaction.path
  val args    = mutaction.nonListArgs
  val model   = path.lastModel
  val where = path.edges match {
    case x if x.isEmpty => path.root
    case x              => x.last.asInstanceOf[NodeEdge].childWhere
  }
  val id = where.fieldValueAsString

  override val action = {
    val relayIds      = TableQuery(new ProjectRelayIdTable(_, project.id))
    val relayQuery    = relayIds += ProjectRelayId(id = id, model.stableIdentifier)
    val createNonList = DatabaseMutationBuilder.createReallyCoolDataItem(project.id, model, args)
    val listActions   = DatabaseMutationBuilder.getDbActionsForNewScalarLists(project, path, mutaction.listArgs)
    val allActions    = List(createNonList, relayQuery) ++ listActions

    DBIO.seq(allActions: _*)
  }

  override val errorMapper = {
    case e: SQLIntegrityConstraintViolationException
        if e.getErrorCode == 1062 && GetFieldFromSQLUniqueException.getFieldOption(args.raw.asRoot.map.keys.toVector, e).isDefined =>
      APIErrors.UniqueConstraintViolation(model.name, GetFieldFromSQLUniqueException.getFieldOption(args.raw.asRoot.map.keys.toVector, e).get)
    case e: SQLIntegrityConstraintViolationException if e.getErrorCode == 1452 =>
      APIErrors.NodeDoesNotExist("")
  }
}

case class DeleteDataItemInterpreter(mutaction: DeleteDataItem) extends DatabaseMutactionInterpreter {
  override val action = DBIO.seq(
    DatabaseMutationBuilder.deleteRelayRow(mutaction.project.id, mutaction.path),
    DatabaseMutationBuilder.deleteDataItem(mutaction.project.id, mutaction.path)
  )
}

case class DeleteDataItemNestedInterpreter(mutaction: DeleteDataItemNested) extends DatabaseMutactionInterpreter {
  override val action = DBIO.seq(
    DatabaseMutationBuilder.deleteRelayRow(mutaction.project.id, mutaction.path),
    DatabaseMutationBuilder.deleteDataItem(mutaction.project.id, mutaction.path)
  )
}

case class DeleteDataItemsInterpreter(mutaction: DeleteDataItems) extends DatabaseMutactionInterpreter {
  val project     = mutaction.project
  val model       = mutaction.model
  val whereFilter = mutaction.whereFilter

  override val action = DBIOAction.seq(
    DatabaseMutationBuilder.deleteRelayIds(project, model, whereFilter),
    DatabaseMutationBuilder.deleteDataItems(project, model, whereFilter)
  )
}

case class DeleteManyRelationChecksInterpreter(mutaction: DeleteManyRelationChecks) extends DatabaseMutactionInterpreter {
  val project = mutaction.project
  val model   = mutaction.model
  val filter  = mutaction.filter

  val fieldsWhereThisModelIsRequired = project.schema.fieldsWhereThisModelIsRequired(model)

  override val action = {
    val requiredChecks = fieldsWhereThisModelIsRequired.map(oldParentFailureTriggerByFieldAndFilter(project, model, filter, _))
    DBIOAction.seq(requiredChecks: _*)
  }

  override def errorMapper = {
    case e: SQLException if e.getErrorCode == 1242 && otherFailingRequiredRelationOnChild(e.getCause.toString).isDefined =>
      throw RequiredRelationWouldBeViolated(project, otherFailingRequiredRelationOnChild(e.getCause.toString).get)
  }

  private def otherFailingRequiredRelationOnChild(cause: String): Option[Relation] = fieldsWhereThisModelIsRequired.collectFirst {
    case f if causedByThisMutactionChildOnly(f, cause) => f.relation.get
  }

  private def causedByThisMutactionChildOnly(field: Field, cause: String) = {
    val parentCheckString = s"`${field.relation.get.relationTableName}` OLDPARENTPATHFAILURETRIGGERBYFIELDANDFILTER WHERE `${field.oppositeRelationSide.get}`"
    cause.contains(parentCheckString) //todo add filter
  }
}

case class DeleteRelationCheckInterpreter(mutaction: DeleteRelationCheck) extends DatabaseMutactionInterpreter {
  val project = mutaction.project
  val path    = mutaction.path

  val fieldsWhereThisModelIsRequired = project.schema.fieldsWhereThisModelIsRequired(path.lastModel)

  override val action = {
    val requiredCheck = fieldsWhereThisModelIsRequired.map(oldParentFailureTriggerByField(project, path, _))
    DBIOAction.seq(requiredCheck: _*)
  }

  override val errorMapper = {
    case e: SQLException if e.getErrorCode == 1242 && otherFailingRequiredRelationOnChild(e.getCause.toString).isDefined =>
      throw RequiredRelationWouldBeViolated(project, otherFailingRequiredRelationOnChild(e.getCause.toString).get)
  }

  private def otherFailingRequiredRelationOnChild(cause: String): Option[Relation] =
    fieldsWhereThisModelIsRequired.collectFirst { case f if causedByThisMutactionChildOnly(f, cause) => f.relation.get }

  private def causedByThisMutactionChildOnly(field: Field, cause: String) = {
    val parentCheckString = s"`${field.relation.get.relationTableName}` OLDPARENTPATHFAILURETRIGGERBYFIELD WHERE `${field.oppositeRelationSide.get}`"

    path.lastEdge match {
      case Some(edge: NodeEdge) => cause.contains(parentCheckString) && cause.contains(parameterString(edge.childWhere))
      case _                    => cause.contains(parentCheckString)
    }
  }
}

object DisableForeignKeyConstraintChecksInterpreter extends DatabaseMutactionInterpreter {
  override val action = DatabaseMutationBuilder.disableForeignKeyConstraintChecks
}

object EnableForeignKeyConstraintChecksInterpreter extends DatabaseMutactionInterpreter {
  override val action = DatabaseMutationBuilder.enableForeignKeyConstraintChecks
}

case class SetScalarListInterpreter(mutaction: SetScalarList) extends DatabaseMutactionInterpreter {
  override val action = DatabaseMutationBuilder.setScalarList(mutaction.project.id, mutaction.path, mutaction.field.name, mutaction.listGCValue)
}

case class SetScalarListToEmptyInterpreter(mutaction: SetScalarListToEmpty) extends DatabaseMutactionInterpreter {
  override val action = DatabaseMutationBuilder.setScalarListToEmpty(mutaction.project.id, mutaction.path, mutaction.field.name)
}

case class TruncateTableInterpreter(mutaction: TruncateTable) extends DatabaseMutactionInterpreter {
  override val action = DatabaseMutationBuilder.truncateTable(mutaction.projectId, mutaction.tableName)
}

case class UpdateDataItemInterpreter(mutaction: UpdateDataItem) extends DatabaseMutactionInterpreter {
  val project        = mutaction.project
  val path           = mutaction.path
  val nonListArgs    = mutaction.nonListArgs
  val nonListActions = DatabaseMutationBuilder.updateDataItemByPath(project.id, path, nonListArgs)
  val listActions    = DatabaseMutationBuilder.getDbActionsForNewScalarLists(project, path, mutaction.listArgs)

  val allActions = listActions :+ nonListActions

  override val action = DBIO.seq(allActions: _*)

  override val errorMapper = {
    // https://dev.mysql.com/doc/refman/5.5/en/error-messages-server.html#error_er_dup_entry
    case e: SQLIntegrityConstraintViolationException
        if e.getErrorCode == 1062 && GetFieldFromSQLUniqueException.getFieldOption(nonListArgs.raw.keys.toVector, e).isDefined =>
      APIErrors.UniqueConstraintViolation(path.lastModel.name, GetFieldFromSQLUniqueException.getFieldOption(nonListArgs.raw.keys.toVector, e).get)

    case e: SQLIntegrityConstraintViolationException if e.getErrorCode == 1452 =>
      APIErrors.NodeNotFoundForWhereError(path.root)

    case e: SQLIntegrityConstraintViolationException if e.getErrorCode == 1048 =>
      APIErrors.FieldCannotBeNull()
  }
}

case class NestedUpdateDataItemInterpreter(mutaction: NestedUpdateDataItem) extends DatabaseMutactionInterpreter { //todo kick this out
  val project        = mutaction.project
  val path           = mutaction.path
  val args           = mutaction.args
  val nonListActions = DatabaseMutationBuilder.updateDataItemByPath(project.id, path, args)
  val listActions    = DatabaseMutationBuilder.getDbActionsForNewScalarLists(project, path, mutaction.listArgs)

  val allActions = listActions :+ nonListActions

  override val action = DBIO.seq(allActions: _*)

  override val errorMapper = {
    // https://dev.mysql.com/doc/refman/5.5/en/error-messages-server.html#error_er_dup_entry
    case e: SQLIntegrityConstraintViolationException
        if e.getErrorCode == 1062 && GetFieldFromSQLUniqueException.getFieldOption(args.raw.keys.toVector, e).isDefined =>
      APIErrors.UniqueConstraintViolation(path.lastModel.name, GetFieldFromSQLUniqueException.getFieldOption(args.raw.keys.toVector, e).get)

    case e: SQLIntegrityConstraintViolationException if e.getErrorCode == 1452 =>
      APIErrors.NodeNotFoundForWhereError(path.root)

    case e: SQLIntegrityConstraintViolationException if e.getErrorCode == 1048 =>
      APIErrors.FieldCannotBeNull()
  }
}

case class UpdateDataItemsInterpreter(mutaction: UpdateDataItems) extends DatabaseMutactionInterpreter {
  override val action = DatabaseMutationBuilder.updateDataItems(mutaction.project.id, mutaction.model, mutaction.updateArgs, mutaction.where)
}

case class UpsertDataItemInterpreter(mutaction: UpsertDataItem) extends DatabaseMutactionInterpreter {
  val model      = mutaction.path.lastModel
  val project    = mutaction.project
  val path       = mutaction.path
  val allArgs    = mutaction.allArgs
  val createArgs = mutaction.allArgs.createArgumentsAsCoolArgs.generateNonListCreateArgs(model, mutaction.createWhere.fieldValueAsString)
  val updateArgs = mutaction.allArgs.updateArgumentsAsCoolArgs.generateNonListUpdateArgs(model)

  override val action = {
    val createActions = DatabaseMutationBuilder.getDbActionsForScalarLists(project.id, path.updatedRoot(createArgs), allArgs.createArgumentsAsCoolArgs)
    val updateActions = DatabaseMutationBuilder.getDbActionsForScalarLists(project.id, path.updatedRoot(updateArgs), allArgs.updateArgumentsAsCoolArgs)
    DatabaseMutationBuilder.upsert(project.id, path, mutaction.createWhere, createArgs, updateArgs, createActions, updateActions)
  }

  override val errorMapper = {
    case e: SQLIntegrityConstraintViolationException
        if e.getErrorCode == 1062 && getFieldOption(createArgs.raw.keys.toVector ++ updateArgs.raw.keys, e).isDefined =>
      APIErrors.UniqueConstraintViolation(model.name, getFieldOption(createArgs.raw.keys.toVector ++ updateArgs.raw.keys, e).get)

    case e: SQLIntegrityConstraintViolationException if e.getErrorCode == 1452 =>
      APIErrors.NodeDoesNotExist("") //todo

    case e: SQLIntegrityConstraintViolationException if e.getErrorCode == 1048 =>
      APIErrors.FieldCannotBeNull(e.getCause.getMessage)
  }
}

case class UpsertDataItemIfInRelationWithInterpreter(mutaction: UpsertDataItemIfInRelationWith) extends DatabaseMutactionInterpreter {
  val project             = mutaction.project
  val extendedPath        = mutaction.path
  val model               = extendedPath.lastModel
  val createWhere         = mutaction.createWhere
  val createArgsWithId    = mutaction.createArgs
  val pathForCreateBranch = extendedPath.lastEdgeToNodeEdge(createWhere)
  val pathForUpdateBranch = mutaction.pathForUpdateBranch
  val actualCreateArgs    = CoolArgs(createArgsWithId.raw).generateNonListCreateArgs(model, createWhere.fieldValueAsString)
  val actualUpdateArgs    = mutaction.updateArgs.nonListScalarArguments(model)

  val scalarListsCreate = DatabaseMutationBuilder.getDbActionsForScalarLists(project.id, pathForCreateBranch, createArgsWithId)
  val scalarListsUpdate = DatabaseMutationBuilder.getDbActionsForScalarLists(project.id, pathForUpdateBranch, mutaction.updateArgs)
  val createCheck       = NestedCreateRelationInterpreter(NestedCreateRelation(project, pathForCreateBranch, false))

  override val action = DatabaseMutationBuilder.upsertIfInRelationWith(
    project = project,
    path = extendedPath,
    createWhere = createWhere,
    createArgs = actualCreateArgs,
    updateArgs = actualUpdateArgs,
    create = scalarListsCreate,
    update = scalarListsUpdate,
    relationMutactions = createCheck
  )

  override val errorMapper = {
    // https://dev.mysql.com/doc/refman/5.5/en/error-messages-server.html#error_er_dup_entry
    case e: SQLIntegrityConstraintViolationException
        if e.getErrorCode == 1062 && getFieldOption(mutaction.createArgs.raw.keys.toVector ++ mutaction.updateArgs.raw.keys, e).isDefined =>
      APIErrors.UniqueConstraintViolation(model.name, getFieldOption(mutaction.createArgs.raw.keys.toVector ++ mutaction.updateArgs.raw.keys, e).get)

    case e: SQLIntegrityConstraintViolationException if e.getErrorCode == 1452 =>
      APIErrors.NodeDoesNotExist("") //todo

    case e: SQLIntegrityConstraintViolationException if e.getErrorCode == 1048 =>
      APIErrors.FieldCannotBeNull()

    case e: SQLException if e.getErrorCode == 1242 && createCheck.causedByThisMutaction(pathForCreateBranch, e.getCause.toString) =>
      throw RequiredRelationWouldBeViolated(project, extendedPath.lastRelation_!)
  }
}

case class VerifyConnectionInterpreter(mutaction: VerifyConnection) extends DatabaseMutactionInterpreter {
  val project = mutaction.project
  val path    = mutaction.path

  override val action = DatabaseMutationBuilder.connectionFailureTrigger(project, path)

  override val errorMapper = {
    case e: SQLException if e.getErrorCode == 1242 && causedByThisMutaction(e.getCause.toString) => throw APIErrors.NodesNotConnectedError(path)
  }

  private def causedByThisMutaction(cause: String) = {
    val string = s"`${path.lastRelation_!.relationTableName}` CONNECTIONFAILURETRIGGERPATH WHERE "

    path.lastEdge_! match {
      case _: ModelEdge   => cause.contains(string ++ s" `${path.parentSideOfLastEdge}`")
      case edge: NodeEdge => cause.contains(string ++ s" `${path.childSideOfLastEdge}`") && cause.contains(parameterString(edge.childWhere))
    }
  }
}

case class VerifyWhereInterpreter(mutaction: VerifyWhere) extends DatabaseMutactionInterpreter {
  val project = mutaction.project
  val where   = mutaction.where

  override val action = DatabaseMutationBuilder.whereFailureTrigger(project, where)

  override val errorMapper = {
    case e: SQLException if e.getErrorCode == 1242 && causedByThisMutaction(e.getCause.toString) => throw APIErrors.NodeNotFoundForWhereError(where)
  }

  private def causedByThisMutaction(cause: String) = {
    val modelString = s"`${where.model.name}` WHEREFAILURETRIGGER WHERE `${where.field.name}`"
    cause.contains(modelString) && cause.contains(parameterString(where))
  }
}

case class CreateDataItemsImportInterpreter(mutaction: CreateDataItemsImport) extends DatabaseMutactionInterpreter {
  override val action = DatabaseMutationBuilder.createDataItemsImport(mutaction)
}

case class CreateRelationRowsImportInterpreter(mutaction: CreateRelationRowsImport) extends DatabaseMutactionInterpreter {
  override val action = DatabaseMutationBuilder.createRelationRowsImport(mutaction)
}

case class PushScalarListsImportInterpreter(mutaction: PushScalarListsImport) extends DatabaseMutactionInterpreter {
  override val action = DatabaseMutationBuilder.pushScalarListsImport(mutaction)
}
