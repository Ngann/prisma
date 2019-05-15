mod postgresql;
mod sqlite;

use crate::Transactional;
use std::marker::PhantomData;

pub use postgresql::*;
pub use sqlite::*;

/// A wrapper for relational databases due to trait restrictions. Implements the
/// needed traits.
pub struct SqlDatabase<'a, T, R: 'a>
where
    T: Transactional,
    R: RelatedNodesBuilder,
{
    pub executor: T,
    _phantom_data: PhantomData<&'a R>,
}

impl<T, R> SqlDatabase<T, R>
where
    T: Transactional,
{
    pub fn new(executor: T, database_type: DatabaseType) -> Self {
        Self {
            executor,
            _phantom_data: PhantomData,
        }
    }
}
