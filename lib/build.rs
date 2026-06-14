fn main() {
    #[cfg(feature = "uniffi")]
    {
        uniffi_build::generate_scaffolding("src/dep_lens.udl").unwrap();
    }
}
