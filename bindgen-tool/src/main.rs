use uniffi_bindgen::bindings::kotlin::KotlinBindingGenerator;
use uniffi_bindgen::generate_bindings;
use std::path::PathBuf;

fn main() {
    let udl_path = PathBuf::from("../lib/src/dep_lens.udl");
    let out_dir = PathBuf::from("../lib/kotlin");
    
    println!("Generating Kotlin bindings from UDL: {:?}", udl_path);
    println!("Output directory: {:?}", out_dir);
    
    std::fs::create_dir_all(&out_dir).expect("Failed to create output directory");
    
    generate_bindings(
        &udl_path,
        &out_dir,
        &[Box::new(KotlinBindingGenerator)],
        Some("dep_lens_lib"),
        None,
        None,
    ).expect("Failed to generate Kotlin bindings");
    
    println!("Kotlin bindings generated successfully!");
}
