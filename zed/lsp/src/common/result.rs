#[derive(Clone, Debug)]
pub enum Result {
    Success,
    Failure,
    Pending,
    None,
}

#[derive(Clone, Debug)]
pub struct ResultWrapper<T> {
    pub result: Result,
    pub data: Option<T>,
}
