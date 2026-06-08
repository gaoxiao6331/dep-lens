use crate::lsp::{InlayHint, Range, TextDocument};

pub trait BaseDepLensInlayProvider {
    fn is_file_supported(&self, document: &TextDocument) -> bool;
    fn provide_inlay_hints_for_document(
        &self,
        document: &TextDocument,
        range: &Range,
    ) -> Vec<InlayHint>;

    fn provide_inlay_hints(&self, document: &TextDocument, range: &Range) -> Vec<InlayHint> {
        if !self.is_file_supported(document) {
            return Vec::new();
        }
        self.provide_inlay_hints_for_document(document, range)
    }
}
