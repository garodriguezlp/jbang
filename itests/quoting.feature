@java9orhigher
Feature: jshell

Background:
* if (javaversion == 8) karate.abort()
* if (windows) karate.abort()



Scenario: piping code via stdin
When command('echo \'System.out.println(\"Hello World\")\' | jbang -')
Then match out == "Hello World\n"