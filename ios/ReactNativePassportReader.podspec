require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json')))

Pod::Spec.new do |s|
  s.name           = 'ReactNativePassportReader'
  s.version        = package['version']
  s.summary        = package['description']
  s.description    = package['description']
  s.homepage       = package['homepage']
  s.license        = package['license']
  s.author         = package['author']
  s.source         = { :git => package['repository']['url'], :tag => s.version.to_s }
  s.platforms      = { ios: '15.1' }

  s.swift_version  = '5.9'
  s.source_files   = '**/*.swift'
  s.resources      = ['Resources/*.pem']

  s.dependency 'ExpoModulesCore'
  s.dependency 'NFCPassportReader', '~> 2.1'
end
