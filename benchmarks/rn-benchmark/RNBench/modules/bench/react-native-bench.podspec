require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "react-native-bench"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = "https://example.invalid/rn-benchmark"
  s.license      = "MIT"
  s.authors      = { "rn-benchmark" => "noreply@example.invalid" }
  s.platforms    = { :ios => min_ios_version_supported }
  s.source       = { :path => "." }

  s.source_files = "ios/**/*.{h,m,mm,cpp}"

  # Pulls in React-Core, ReactCommon/turbomodule and the generated spec headers, and sets the
  # new-architecture compiler flags. Provided by react_native_pods.rb, which the app's Podfile
  # already requires.
  install_modules_dependencies(s)
end
