mod row_number;

pub use row_number::RelatedNodesWithRowNumber;

use crate::{cursor_condition::CursorCondition, ordering::Ordering};
use connector::QueryArguments;
use prisma_models::prelude::*;
use prisma_query::ast::*;
use std::sync::Arc;

pub trait RelatedNodesQueryBuilder<'a> {
    const BASE_TABLE_ALIAS: &'static str = "prismaBaseTableAlias";
    const ROW_NUMBER_ALIAS: &'static str = "prismaRowNumberAlias";
    const ROW_NUMBER_TABLE_ALIAS: &'static str = "prismaRowNumberTableAlias";

    fn new(
        from_field: Arc<RelationField>,
        from_node_ids: &'a [GraphqlId],
        query_arguments: QueryArguments,
        selected_fields: &'a SelectedFields,
    ) -> Self;

    fn with_pagination(self) -> Select;

    fn relation(&self) -> RelationFieldRef;
    fn related_model(&self) -> ModelRef;
    fn selected_fields(&self) -> &SelectedFields;
    fn cursor_condition(&self) -> &CursorCondition;

    fn without_pagination(self) -> Select {
        let relation_side_column = self.relation_side_column();
        let opposite_relation_side_column = self.opposite_relation_side_column();
        let base_query = self.base_query();
        let cursor_condition = self.cursor_condition;

        // TODO: prisma query crate slice handling
        let conditions = relation_side_column
            .clone()
            .in_selection(self.from_node_ids.to_owned())
            .and(cursor_condition)
            .and(self.conditions);

        Ordering::internal(
            opposite_relation_side_column,
            self.order_by.as_ref(),
            self.reverse_order,
        )
        .into_iter()
        .fold(base_query.so_that(conditions), |acc, ord| acc.order_by(ord))
    }

    fn base_query(&self) -> Select {
        let select = Select::from_table(self.related_model().table());

        self.selected_fields()
            .columns()
            .into_iter()
            .fold(select, |acc, col| acc.column(col.clone()))
            .inner_join(
                self.relation_table()
                    .on(self.id_column().equals(self.opposite_relation_side_column())),
            )
    }

    fn relation_table(&self) -> Table {
        self.relation().relation_table().alias(Relation::TABLE_ALIAS)
    }

    fn relation_side_column(&self) -> Column {
        self.relation()
            .column_for_relation_side(self.from_field.relation_side)
            .table(Relation::TABLE_ALIAS)
    }

    fn opposite_relation_side_column(&self) -> Column {
        self.relation()
            .column_for_relation_side(self.from_field.relation_side.opposite())
            .table(Relation::TABLE_ALIAS)
    }

    fn id_column(&self) -> Column {
        self.related_model().id_column()
    }
}
