import Foundation

@_silgen_name("psd2live_launch")
func psd2live_launch(
	_ resources: UnsafePointer<CChar>,
	_ argc: Int32,
	_ argv: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>
) -> Int32

let resources = Bundle.main.resourcePath ?? "."
let status = resources.withCString { path in
	psd2live_launch(path, CommandLine.argc, CommandLine.unsafeArgv)
}
exit(status)
