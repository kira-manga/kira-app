require "base64"
require "open3"
require "rexml/document"
require "time"

module KiraRelease
  module PlistReader
    module_function

    def read(path)
      contents = File.binread(path)
      if contents.start_with?("bplist")
        contents, _error, status = Open3.capture3(
          "/usr/bin/plutil",
          "-convert",
          "xml1",
          "-o",
          "-",
          path
        )
        raise "Invalid plist document" unless status.success?
      end

      document = REXML::Document.new(contents)
      root = document.elements["plist"]&.elements&.to_a&.first
      raise "Invalid plist document" unless root

      parse(root)
    rescue REXML::ParseException, ArgumentError
      raise "Invalid plist document"
    end

    def parse(element)
      case element.name
      when "dict"
        result = {}
        children = element.elements.to_a
        index = 0
        while index < children.length
          key = children[index]
          value = children[index + 1]
          raise "Malformed plist dictionary" unless key&.name == "key" && value

          result[key.text.to_s] = parse(value)
          index += 2
        end
        result
      when "array"
        element.elements.to_a.map { |child| parse(child) }
      when "string", "key"
        element.text.to_s
      when "data"
        Base64.decode64(element.text.to_s)
      when "date"
        Time.xmlschema(element.text.to_s)
      when "integer"
        Integer(element.text.to_s, 10)
      when "real"
        Float(element.text.to_s)
      when "true"
        true
      when "false"
        false
      else
        raise "Unsupported plist value type"
      end
    end
  end
end
