import Foundation
import AppKit

@_cdecl("psd2live_show_error")
func psd2live_show_error(_ message: UnsafePointer<CChar>) {
	let app = NSApplication.shared
	app.setActivationPolicy(.regular)
	app.activate(ignoringOtherApps: true)
	let alert = NSAlert()
	alert.messageText = "PSD2Live"
	alert.informativeText = String(cString: message)
	alert.alertStyle = .critical
	alert.addButton(withTitle: "OK")
	alert.runModal()
}

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
