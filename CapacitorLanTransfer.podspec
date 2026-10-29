require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  # Must match the name Capacitor's CLI derives from the package name, and live
  # at the package root: that is where `pod 'CapacitorLanTransfer', :path =>` looks.
  s.name = 'CapacitorLanTransfer'
  s.version = package['version']
  s.summary = package['description']
  s.license = package['license']
  s.homepage = package['repository']['url']
  s.author = package['author']
  s.source = { :git => package['repository']['url'], :tag => s.version.to_s }

  s.ios.deployment_target = '13.0'
  s.swift_version = '5.7'

  s.source_files = 'ios/Plugin/**/*.{swift,h,m}'
  s.dependency 'Capacitor'
  s.framework = 'Network'
end