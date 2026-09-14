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

          result[scalar_text(key)] = parse(value)
          index += 2
        end
        result
      when "array"
        element.elements.to_a.map { |child| parse(child) }
      when "string", "key"
        scalar_text(element)
      when "data"
        Base64.decode64(scalar_text(element))
      when "date"
        Time.xmlschema(scalar_text(element))
      when "integer"
        Integer(scalar_text(element), 10)
      when "real"
        Float(scalar_text(element))
      when "true"
        true
      when "false"
        false
      else
        raise "Unsupported plist value type"
      end
    end

    def scalar_text(element)
      element.children.map do |child|
        case child
        when REXML::Text
          # CData is a Text subclass. Element#text alone drops later text/CDATA nodes.
          child.value
        when REXML::Comment
          ""
        else
          raise "Malformed plist scalar"
        end
      end.join
    end
  end
end
