# NSF Router

This project can be installed as an OSGi plugin on an HCL Domino server to allow custom routing within an NSF.

## Usage

Install the plugin on your server in your preferred way, such as via an NSF Update Site. Once it's installed, add a file resource named `nsfrouter.properties` to the design of your NSF. This file should contain regular-expression keys to their replacement values. For example:

```
foo=somepage.xsp
foo/(.*)=somepage.xsp?key=$1
```

Note: this file can also be placed in `Code/Java` or other equivalent places in the root classpath. When created as a File Resource design element, you should mark it as not shown on the web.

## Regex Matching

Regular Expression matching and replacement is done with the standard Java `java.util.regex` capabilities. Additionally, they must match the entire checked path info portion, though the `^` and `$` terminators are optional. For example, the pattern `foo/(\w+)` will match `foo/bar`, but not `foo/bar/` or `foo/bar/baz`.

Bear in mind that regexes should be encoded and escaped according to the rules of Java Properties files. In practice, this primarily means that backslashes must be doubled. For example, the a replacement for the pattern `foo/(\w+)` should be represented like this in the properties file:

```
foo/(\\w+)=somepage.xsp?bit=$1
```

## Limitations

This project will not redirect URLs that are matched by a different `HttpService` implementation. In practice, this means that it won't match ".xsp" URLs or other specialty paths handled by the Java layer of Domino's HTTP stack.

## License

This project is licensed under the Apache License 2.0.
