// Sample shared library step — used by anvil.compat.jenkins.libraries-test.
// Demonstrates a vars/*.groovy file that takes a String argument.

def call(String name = 'world') {
    echo "hello, ${name}"
}
