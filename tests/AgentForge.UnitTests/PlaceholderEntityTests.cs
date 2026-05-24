using AgentForge.Domain;
using Shouldly;
using Xunit;

namespace AgentForge.UnitTests;

public sealed class PlaceholderEntityTests
{
    [Fact]
    public void Constructor_AssignsProperties()
    {
        var entity = new PlaceholderEntity(Id: "id-1", Name: "alpha");

        entity.Id.ShouldBe("id-1");
        entity.Name.ShouldBe("alpha");
    }
}
